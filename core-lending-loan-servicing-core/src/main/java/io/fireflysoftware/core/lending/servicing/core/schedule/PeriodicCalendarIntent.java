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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Captures caller-owned input before subscription; no financial conventions are inferred. */
public record PeriodicCalendarIntent(BigDecimal principal, BigDecimal annualRatePercent, int term,
        PaymentFrequencyEnum frequency, AmortizationMethodEnum method, LocalDate firstDueDate,
        BigDecimal installmentAmount) {
    public PeriodicCalendarIntent {
        money(principal, false);
        if (annualRatePercent == null || annualRatePercent.scale() > 4 || annualRatePercent.precision() > 7
                || annualRatePercent.signum() < 0 || annualRatePercent.compareTo(new BigDecimal("100")) > 0
                || term < 1 || term > 600 || frequency == null || firstDueDate == null) throw invalid();
        if (installmentAmount != null) money(installmentAmount, false);
    }

    public static PeriodicCalendarIntent capture(StoreAmortizationCalendarRequest request) {
        Objects.requireNonNull(request, "Calendar request required");
        if (request.getTerm() == null) throw invalid();
        return new PeriodicCalendarIntent(request.getPrincipal(), request.getAnnualRatePercent(), request.getTerm(),
                request.getPaymentFrequency(), request.getAmortizationMethod(), request.getFirstDueDate(), request.getInstallmentAmount());
    }

    public List<AmortizationCalendarCalculator.Installment> stage(LoanServicingCase loan) {
        if (loan == null || loan.getLoanServicingCaseId() == null
                || !equal(principal, loan.getPrincipalAmount()) || !equal(annualRatePercent, loan.getInterestRate())
                || !Objects.equals(term, loan.getLoanTerm()) || frequency != loan.getPaymentFrequency()
                || method != null && method != loan.getAmortizationMethod()) throw invalid();
        var agreedMethod = loan.getAmortizationMethod();
        if (agreedMethod == null) throw invalid();
        // This existing nominal monthly calculator has no irregular-period or alternate convention contract.
        if (loan.getServicingStatus() != ServicingStatusEnum.PENDING && loan.getServicingStatus() != ServicingStatusEnum.ACTIVE) throw unsupported();
        if (frequency != PaymentFrequencyEnum.MONTHLY
                || loan.getInterestCalculationMethod() != InterestCalculationMethodEnum.REDUCING_BALANCE
                || loan.getCompoundingFrequency() != CompoundingFrequencyEnum.MONTHLY
                || loan.getDayCountConvention() != DayCountConventionEnum.THIRTY_360
                || loan.getOriginationDate() == null || loan.getOriginationDate().getDayOfMonth() != 1
                || !firstDueDate.equals(loan.getOriginationDate().plusMonths(1))
                || !Objects.equals(loan.getMaturityDate(), loan.getOriginationDate().plusMonths(term))
                || agreedMethod == AmortizationMethodEnum.BALLOON_PAYMENT || agreedMethod == AmortizationMethodEnum.BULLET) throw unsupported();
        var rows = AmortizationCalendarCalculator.generate(principal, annualRatePercent, term, frequency, agreedMethod, firstDueDate, null);
        if (installmentAmount != null && (agreedMethod != AmortizationMethodEnum.EQUAL_INSTALLMENT
                || !equal(installmentAmount, rows.getFirst().totalDue()))) throw invalid();
        BigDecimal remaining = principal;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            money(row.principalDue(), true); money(row.interestDue(), true); money(row.feeDue(), true); money(row.totalDue(), true);
            remaining = remaining.subtract(row.principalDue());
            if (row.number() != index + 1 || row.feeDue().signum() != 0
                    || row.totalDue().compareTo(row.principalDue().add(row.interestDue()).add(row.feeDue())) != 0
                    || remaining.signum() < 0 || remaining.compareTo(row.remainingBalance()) != 0
                    || !row.dueDate().equals(firstDueDate.plusMonths(index))) throw invalid();
        }
        if (rows.size() != term || remaining.signum() != 0) throw invalid();
        return List.copyOf(rows);
    }

    public String digest() {
        return digest(String.join("\n", "LENDING_PERIODIC_CALENDAR_V1", principal.toPlainString(), annualRatePercent.toPlainString(),
                Integer.toString(term), frequency.name(), method == null ? "PERSISTED_METHOD" : method.name(), firstDueDate.toString(),
                installmentAmount == null ? "NONE" : installmentAmount.toPlainString()));
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean equal(BigDecimal left, BigDecimal right) { return right != null && left.compareTo(right) == 0; }
    private static void money(BigDecimal value, boolean zero) {
        if (value == null || value.scale() > 2 || (long) value.precision() - value.scale() > 15
                || (zero ? value.signum() < 0 : value.signum() <= 0)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Exact supported persisted calendar terms required"); }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Loan convention is outside the regular monthly calendar slice"); }
    @Override public String toString() { return "PeriodicCalendarIntent[redacted]"; }
}
