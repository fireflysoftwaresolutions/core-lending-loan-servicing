/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fireflysoftware.core.lending.servicing.core.schedule;

import io.fireflysoftware.core.lending.servicing.interfaces.dtos.StoreAmortizationCalendarRequest;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.*;
import io.fireflysoftware.core.lending.servicing.models.entities.LoanServicingCase;
import java.math.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PeriodicCalendarIntentTest {
    private static LoanServicingCase loan(String principal, String rate, int term) {
        return LoanServicingCase.builder().loanServicingCaseId(UUID.randomUUID()).servicingStatus(ServicingStatusEnum.PENDING)
                .principalAmount(new BigDecimal(principal)).interestRate(new BigDecimal(rate)).loanTerm(term)
                .paymentFrequency(PaymentFrequencyEnum.MONTHLY).amortizationMethod(AmortizationMethodEnum.EQUAL_INSTALLMENT)
                .interestCalculationMethod(InterestCalculationMethodEnum.REDUCING_BALANCE).dayCountConvention(DayCountConventionEnum.THIRTY_360)
                .compoundingFrequency(CompoundingFrequencyEnum.MONTHLY).originationDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2026, 1, 1).plusMonths(term)).build();
    }

    private static StoreAmortizationCalendarRequest request(String principal, String rate, int term) {
        return AuditedCalendarServiceTest.request(principal, rate, term, LocalDate.of(2026, 2, 1));
    }

    @Test void omittedOptionalMethodUsesTheActualPersistedMethod() {
        var request = request("100.00", "0.00", 3); request.setAmortizationMethod(null);
        var rows = PeriodicCalendarIntent.capture(request).stage(loan("100.00", "0.00", 3));
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(AmortizationCalendarCalculator.Installment::principalDue)
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
    }

    @Test void centRemainderConservesPrincipalAndClosesAtZero() {
        var rows = PeriodicCalendarIntent.capture(request("100.00", "0.00", 3)).stage(loan("100.00", "0.00", 3));
        assertThat(rows.stream().map(AmortizationCalendarCalculator.Installment::principalDue).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(rows.getLast().remainingBalance()).isEqualByComparingTo("0.00");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.totalDue()).isEqualByComparingTo(row.principalDue().add(row.interestDue()).add(row.feeDue()));
            assertThat(row.principalDue().scale()).isEqualTo(2);
            assertThat(row.feeDue()).isEqualByComparingTo("0.00");
        });
    }

    @Test void suppliedPriceCannotIntroduceAnImplicitBalloon() {
        var request = request("1200.00", "12.00", 12); request.setInstallmentAmount(BigDecimal.ONE);
        assertThatThrownBy(() -> PeriodicCalendarIntent.capture(request).stage(loan("1200.00", "12.00", 12))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void giantNegativeExponentRefusesBeforeDigestAllocation() {
        var request = request("100.00", "0.00", 3); request.setPrincipal(new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE));
        assertThatThrownBy(() -> PeriodicCalendarIntent.capture(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unsupportedDayCountIsNotSilentlyNormalized() {
        var persisted = loan("1200.00", "12.00", 12); persisted.setDayCountConvention(DayCountConventionEnum.ACTUAL_365);
        assertThatThrownBy(() -> PeriodicCalendarIntent.capture(request("1200.00", "12.00", 12)).stage(persisted)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void irregularFirstPeriodIsNotPricedAsAFullNominalMonth() {
        var persisted = loan("1200.00", "12.00", 12); persisted.setOriginationDate(LocalDate.of(2026, 1, 15));
        assertThatThrownBy(() -> PeriodicCalendarIntent.capture(request("1200.00", "12.00", 12)).stage(persisted)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void terminalCaseCannotAcquireANewCalendar() {
        var persisted = loan("1200.00", "12.00", 12); persisted.setServicingStatus(ServicingStatusEnum.PAID_OFF);
        assertThatThrownBy(() -> PeriodicCalendarIntent.capture(request("1200.00", "12.00", 12)).stage(persisted)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void capturedTermsDoNotFollowLaterDtoMutation() {
        var request = request("1200.00", "12.00", 12); var captured = PeriodicCalendarIntent.capture(request);
        request.setPrincipal(BigDecimal.ZERO); request.setTerm(1); request.setFirstDueDate(LocalDate.of(2030, 1, 1));
        assertThat(captured.stage(loan("1200.00", "12.00", 12))).hasSize(12);
    }

    @Test void equalPrincipalAndInterestOnlyKeepTheirActualCashFlowShapes() {
        for (var method : new AmortizationMethodEnum[]{AmortizationMethodEnum.EQUAL_PRINCIPAL, AmortizationMethodEnum.INTEREST_ONLY}) {
            var request = request("100.00", "12.00", 3); request.setAmortizationMethod(method);
            var persisted = loan("100.00", "12.00", 3); persisted.setAmortizationMethod(method);
            var rows = PeriodicCalendarIntent.capture(request).stage(persisted);
            assertThat(rows.stream().map(AmortizationCalendarCalculator.Installment::principalDue).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
            assertThat(rows.getLast().remainingBalance()).isEqualByComparingTo("0.00");
            if (method == AmortizationMethodEnum.INTEREST_ONLY) {
                assertThat(rows.getFirst().principalDue()).isEqualByComparingTo("0.00");
                assertThat(rows.getLast().principalDue()).isEqualByComparingTo("100.00");
            } else assertThat(rows.getFirst().principalDue()).isEqualByComparingTo("33.33");
        }
    }

    @Test void bulletAndBalloonCannotBeReinterpretedAsAnotherAmortizationMethod() {
        for (var method : new AmortizationMethodEnum[]{AmortizationMethodEnum.BULLET, AmortizationMethodEnum.BALLOON_PAYMENT}) {
            var request = request("100.00", "12.00", 3); request.setAmortizationMethod(method);
            var persisted = loan("100.00", "12.00", 3); persisted.setAmortizationMethod(method);
            assertThatThrownBy(() -> PeriodicCalendarIntent.capture(request).stage(persisted)).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
