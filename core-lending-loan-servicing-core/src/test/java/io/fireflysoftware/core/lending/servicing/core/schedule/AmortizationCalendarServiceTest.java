/*
 * Copyright 2025 Firefly Software Foundation
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

import io.fireflysoftware.core.lending.servicing.interfaces.dtos.LoanRepaymentScheduleDTO;
import io.fireflysoftware.core.lending.servicing.interfaces.dtos.RestoreScheduleRequest;
import io.fireflysoftware.core.lending.servicing.interfaces.dtos.ScheduleRevisionDTO;
import io.fireflysoftware.core.lending.servicing.interfaces.dtos.StoreAmortizationCalendarRequest;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.AmortizationMethodEnum;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.PaymentFrequencyEnum;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.ScheduleStatus;
import io.fireflysoftware.core.lending.servicing.models.entities.LoanRepaymentSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmortizationCalendarServiceTest {

    private AuditedCalendarServiceTest.Fixture fixture;
    private final java.util.Map<UUID, LoanRepaymentSchedule> rows = new java.util.AbstractMap<>() {
        @Override public java.util.Set<Entry<UUID, LoanRepaymentSchedule>> entrySet() {
            return fixture.rows().entrySet();
        }
    };
    private AmortizationCalendarOperations service;
    private UUID caseId;

    @BeforeEach
    void setUp(org.junit.jupiter.api.TestInfo test) {
        fixture = new AuditedCalendarServiceTest.Fixture();
        caseId = fixture.caseId;
        if (test.getTestMethod().orElseThrow().getName().startsWith("restructure")) {
            fixture.terms("900.00", "12.00", 3, LocalDate.of(2026, 3, 1));
        } else {
            fixture.terms("1200.00", "12.00", 12, LocalDate.of(2026, 2, 1));
        }
        service = fixture.service;
    }

    @Test
    void calendarIsStoredAndReadBackWithRemainingBalance() {
        List<LoanRepaymentScheduleDTO> stored = service.store(caseId, request(
                new BigDecimal("1200.00"), new BigDecimal("12.00"), 12, LocalDate.of(2026, 2, 1))).block();

        List<LoanRepaymentScheduleDTO> read = service.read(caseId, false).block();

        assertThat(read).hasSize(12);
        assertThat(stored).extracting(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId)
                .containsExactlyElementsOf(read.stream().map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId).toList());
        assertThat(read).allSatisfy(row -> {
            assertThat(row.getLoanServicingCaseId()).isEqualTo(caseId);
            assertThat(row.getDueDate()).isNotNull();
            assertThat(row.getPrincipalDue()).isNotNull();
            assertThat(row.getInterestDue()).isNotNull();
            assertThat(row.getRemainingBalance()).isNotNull();
            assertThat(row.getScheduleStatus()).isEqualTo(ScheduleStatus.CURRENT);
        });
        assertThat(read.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(read.get(0).getInterestDue()).isEqualByComparingTo("12.00");
        assertThat(read.get(11).getDueDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(read.get(11).getRemainingBalance()).isEqualByComparingTo("0.00");
        BigDecimal principal = read.stream()
                .map(LoanRepaymentScheduleDTO::getPrincipalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(principal).isEqualByComparingTo("1200.00");
        assertThat(rows.values()).allSatisfy(row ->
                assertThat(row.getRemainingBalance()).isEqualByComparingTo(
                        read.stream()
                                .filter(item -> item.getLoanRepaymentScheduleId().equals(row.getLoanRepaymentScheduleId()))
                                .findFirst()
                                .orElseThrow()
                                .getRemainingBalance()));
    }

    @Test
    void restructureKeepsThePreviousScheduleOnTheSameCaseAndRestoresIt() {
        service.store(caseId, request(
                new BigDecimal("900.00"), new BigDecimal("12.00"), 3, LocalDate.of(2026, 3, 1))).block();
        List<UUID> originalIds = service.read(caseId, false).block().stream()
                .map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId)
                .toList();

        fixture.terms("600.00", "6.00", 2, LocalDate.of(2026, 4, 1));
        ScheduleRevisionDTO revision = service.replace(caseId, request(
                new BigDecimal("600.00"), new BigDecimal("6.00"), 2, LocalDate.of(2026, 4, 1))).block();

        assertThat(revision.getLoanServicingCaseId()).isEqualTo(caseId);
        assertThat(revision.getPrevious()).hasSize(3);
        assertThat(revision.getCurrent()).hasSize(2);
        assertThat(revision.getCurrent()).allSatisfy(row -> assertThat(row.getLoanServicingCaseId()).isEqualTo(caseId));
        List<LoanRepaymentScheduleDTO> current = service.read(caseId, false).block();
        List<LoanRepaymentScheduleDTO> kept = service.read(caseId, true).block();
        assertThat(current).extracting(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId)
                .containsExactlyElementsOf(revision.getCurrent().stream()
                        .map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId).toList());
        assertThat(kept).hasSize(5);
        assertThat(kept).filteredOn(row -> row.getScheduleStatus() == ScheduleStatus.SUPERSEDED)
                .extracting(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId)
                .containsExactlyInAnyOrderElementsOf(originalIds);

        fixture.terms("900.00", "12.00", 3, LocalDate.of(2026, 3, 1));
        List<LoanRepaymentScheduleDTO> restored = service.restore(caseId, new RestoreScheduleRequest(
                originalIds,
                revision.getCurrent().stream().map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId).toList())).block();

        assertThat(restored).extracting(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId)
                .containsExactlyInAnyOrderElementsOf(originalIds);
        assertThat(restored).allSatisfy(row -> {
            assertThat(row.getLoanServicingCaseId()).isEqualTo(caseId);
            assertThat(row.getScheduleStatus()).isEqualTo(ScheduleStatus.CURRENT);
        });
        assertThat(service.read(caseId, true).block()).hasSize(3);
    }

    private static StoreAmortizationCalendarRequest request(
            BigDecimal principal, BigDecimal rate, int term, LocalDate firstDueDate) {
        return new StoreAmortizationCalendarRequest(
                principal,
                rate,
                term,
                PaymentFrequencyEnum.MONTHLY,
                AmortizationMethodEnum.EQUAL_INSTALLMENT,
                firstDueDate,
                null);
    }
}
