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
package io.fireflysoftware.core.lending.servicing.core.schedule.impl;

import io.fireflysoftware.core.lending.servicing.core.schedule.*;
import io.fireflysoftware.core.lending.servicing.interfaces.dtos.*;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.ScheduleStatus;
import io.fireflysoftware.core.lending.servicing.models.entities.*;
import io.fireflysoftware.core.lending.servicing.models.entities.calendar.CalendarRevision;
import io.fireflysoftware.core.lending.servicing.models.repositories.calendar.CalendarCaseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.fireflyframework.web.error.exceptions.InvalidRequestException;
import org.fireflyframework.web.error.exceptions.ServiceUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.StatementMapper;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.relational.core.query.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

@Service
public final class AuditedAmortizationCalendarService implements AmortizationCalendarOperations {
    private final AuditedCalendarResources owner;
    private final R2dbcEntityTemplate template;
    private final CalendarCaseRepository cases;

    @Autowired
    public AuditedAmortizationCalendarService(ObjectProvider<AuditedCalendarResources> resources) {
        this(resources.getIfAvailable());
    }

    public AuditedAmortizationCalendarService(AuditedCalendarResources resources) {
        owner = resources;
        template = resources == null ? null : resources.template();
        cases = resources == null ? null : new R2dbcRepositoryFactory(template).getRepository(CalendarCaseRepository.class);
    }

    @Override
    public Mono<List<LoanRepaymentScheduleDTO>> store(UUID caseId, StoreAmortizationCalendarRequest request) {
        final PeriodicCalendarIntent intent;
        try { intent = capture(request); }
        catch (RuntimeException invalid) { return Mono.error(invalid); }
        return mutate(caseId, intent, false).map(ScheduleRevisionDTO::getCurrent);
    }

    @Override
    public Mono<ScheduleRevisionDTO> replace(UUID caseId, StoreAmortizationCalendarRequest request) {
        final PeriodicCalendarIntent intent;
        try { intent = capture(request); }
        catch (RuntimeException invalid) { return Mono.error(invalid); }
        return mutate(caseId, intent, true);
    }

    private Mono<ScheduleRevisionDTO> mutate(UUID caseId, PeriodicCalendarIntent intent, boolean replacement) {
        return Mono.defer(() -> {
            requireOwner();
            String action = replacement ? "lending.calendar.replace" : "lending.calendar.store";
            return owner.execute(caseId, action, intent.digest(), permit -> lockedCase(caseId).flatMap(loan -> {
                var staged = intent.stage(loan);
                return rows(caseId).flatMap(all -> {
                    if (all.size() > 1200 - staged.size()) return Mono.error(stale());
                    var previous = active(all);
                    if (replacement ? previous.isEmpty() : !previous.isEmpty()) return Mono.error(stale());
                    if (!previous.isEmpty()) requireCompleteUnpaidSchedule(previous);
                    var inserted = staged.stream().map(row -> newRow(caseId, row)).toList();
                    return revision(caseId, permit).map(revision -> {
                        String evidence = effectDigest(action, caseId, intent.digest(), loan, previous, inserted, revision);
                        return new AuditedCalendarResources.Plan<>(evidence, record ->
                                advance(revision, permit, intent.digest())
                                .thenMany(Flux.fromIterable(previous).concatMap(row -> changeStatus(row, ScheduleStatus.SUPERSEDED)))
                                .thenMany(Flux.fromIterable(inserted).concatMap(this::insert))
                                .then(rows(caseId)).map(observed -> new ScheduleRevisionDTO(caseId,
                                        observedRows(observed, previous, ScheduleStatus.SUPERSEDED),
                                        observedRows(observed, inserted, ScheduleStatus.CURRENT))));
                    });
                });
            }));
        });
    }

    @Override
    public Mono<List<LoanRepaymentScheduleDTO>> read(UUID caseId, boolean includeSuperseded) {
        return Mono.defer(() -> {
            requireOwner();
            String intent = PeriodicCalendarIntent.digest("CALENDAR_READ_V1\n" + caseId + "\n" + includeSuperseded);
            return owner.execute(caseId, "lending.calendar.read", intent, permit -> lockedCase(caseId)
                    .then(rows(caseId)).map(all -> new AuditedCalendarResources.Plan<>(
                            effectDigest("read", caseId, intent, null, all, List.of(), Optional.empty()),
                            record -> Mono.just(all.stream().filter(row -> includeSuperseded || row.getScheduleStatus() != ScheduleStatus.SUPERSEDED)
                                    .map(row -> dto(row, row.getScheduleStatus())).toList()))));
        });
    }

    @Override
    public Mono<List<LoanRepaymentScheduleDTO>> restore(UUID caseId, RestoreScheduleRequest request) {
        final List<UUID> previousIds;
        final List<UUID> replacementIds;
        try {
            previousIds = ids(request.getPreviousScheduleIds());
            replacementIds = ids(request.getReplacementScheduleIds());
            if (!Collections.disjoint(previousIds, replacementIds)) throw stale();
        } catch (RuntimeException invalid) { return Mono.error(invalid); }
        return Mono.defer(() -> {
            requireOwner();
            String intent = PeriodicCalendarIntent.digest("CALENDAR_RESTORE_V1\n" + caseId + "\n" + previousIds + "\n" + replacementIds);
            return owner.execute(caseId, "lending.calendar.restore", intent, permit -> lockedCase(caseId).flatMap(loan -> rows(caseId).flatMap(all -> {
                var current = active(all);
                if (!new HashSet<>(current.stream().map(LoanRepaymentSchedule::getLoanRepaymentScheduleId).toList())
                        .equals(new HashSet<>(replacementIds))) return Mono.error(stale());
                var previous = all.stream().filter(row -> previousIds.contains(row.getLoanRepaymentScheduleId())).toList();
                if (previous.size() != previousIds.size() || previous.stream().anyMatch(row -> row.getScheduleStatus() != ScheduleStatus.SUPERSEDED)) return Mono.error(stale());
                requireCompleteUnpaidSchedule(current);
                requireCompleteUnpaidSchedule(previous);
                var restoring = new PeriodicCalendarIntent(loan.getPrincipalAmount(), loan.getInterestRate(), loan.getLoanTerm(),
                        loan.getPaymentFrequency(), loan.getAmortizationMethod(), previous.getFirst().getDueDate(), null);
                var expected = restoring.stage(loan);
                if (expected.size() != previous.size()) return Mono.error(stale());
                for (int index = 0; index < expected.size(); index++) if (!matches(previous.get(index), expected.get(index))) return Mono.error(stale());
                return revision(caseId, permit).map(revision -> new AuditedCalendarResources.Plan<>(
                        effectDigest("restore", caseId, intent, loan, current, previous, revision), record ->
                        advance(revision, permit, intent)
                        .thenMany(Flux.fromIterable(current).concatMap(row -> exactOne(template.delete(Query.query(fence(row)), LoanRepaymentSchedule.class))))
                        .thenMany(Flux.fromIterable(previous).concatMap(row -> changeStatus(row, ScheduleStatus.CURRENT)))
                        .then(rows(caseId)).map(observed -> {
                            if (observed.stream().anyMatch(row -> replacementIds.contains(row.getLoanRepaymentScheduleId()))) throw stale();
                            return observedRows(observed, previous, ScheduleStatus.CURRENT);
                        })));
            })));
        });
    }

    private static PeriodicCalendarIntent capture(StoreAmortizationCalendarRequest request) {
        if (request == null) throw invalidInput();
        try { return PeriodicCalendarIntent.capture(request); }
        catch (IllegalArgumentException invalid) { throw invalidInput(); }
    }

    private static InvalidRequestException invalidInput() {
        return new InvalidRequestException("CALENDAR_INPUT_INVALID", "Exact supported persisted calendar terms required");
    }

    private Mono<LoanServicingCase> lockedCase(UUID caseId) {
        return cases.findByLoanServicingCaseId(caseId).filter(row -> caseId.equals(row.getLoanServicingCaseId())).switchIfEmpty(Mono.error(stale()));
    }

    private Mono<List<LoanRepaymentSchedule>> rows(UUID caseId) {
        return template.select(Query.query(Criteria.where("loanServicingCaseId").is(caseId))
                        .sort(org.springframework.data.domain.Sort.by("installmentNumber", "loanRepaymentScheduleId")), LoanRepaymentSchedule.class)
                .take(1201).collectList().flatMap(rows -> rows.size() > 1200
                        || rows.stream().anyMatch(row -> row.getLoanRepaymentScheduleId() == null || !caseId.equals(row.getLoanServicingCaseId()))
                        || rows.stream().map(LoanRepaymentSchedule::getLoanRepaymentScheduleId).distinct().count() != rows.size()
                        ? Mono.error(stale()) : Mono.just(List.copyOf(rows)));
    }

    private Mono<Optional<CalendarRevision>> revision(UUID caseId, CalendarAuditAdmission.Permit permit) {
        return template.selectOne(Query.query(Criteria.where("caseId").is(caseId)), CalendarRevision.class)
                .map(row -> {
                    if (!caseId.equals(row.caseId()) || !row.resourceTenantId().equals(permit.resourceTenantId()) || !row.bindingId().equals(permit.bindingId())
                            || row.bindingVersion() != permit.bindingVersion() || !row.bindingDigest().equals(permit.bindingDigest())
                            || row.revision() == null || row.revision() < 0 || row.revision() == Long.MAX_VALUE
                            || row.intentDigest() == null || !row.intentDigest().matches("[0-9a-f]{64}")) throw stale();
                    return Optional.of(row);
                }).defaultIfEmpty(Optional.empty());
    }

    private Mono<Void> advance(Optional<CalendarRevision> previous, CalendarAuditAdmission.Permit permit, String intent) {
        if (previous.isEmpty()) return insert(new CalendarRevision(permit.caseId(), permit.resourceTenantId(), permit.bindingId(),
                permit.bindingVersion(), permit.bindingDigest(), 0L, intent));
        var row = previous.orElseThrow();
        var criteria = Criteria.where("caseId").is(row.caseId()).and("resourceTenantId").is(row.resourceTenantId())
                .and("bindingId").is(row.bindingId()).and("bindingVersion").is(row.bindingVersion())
                .and("bindingDigest").is(row.bindingDigest()).and("revision").is(row.revision()).and("intentDigest").is(row.intentDigest());
        return exactOne(template.update(Query.query(criteria), Update.update("revision", row.revision() + 1).set("intentDigest", intent), CalendarRevision.class));
    }

    private Mono<Void> changeStatus(LoanRepaymentSchedule row, ScheduleStatus status) {
        return exactOne(template.update(Query.query(fence(row)), Update.update("scheduleStatus", status)
                .set("updatedAt", LocalDateTime.ofInstant(owner.clock().instant(), ZoneOffset.UTC)), LoanRepaymentSchedule.class));
    }

    private static Criteria fence(LoanRepaymentSchedule row) {
        Criteria criteria = Criteria.where("loanRepaymentScheduleId").is(row.getLoanRepaymentScheduleId())
                .and("loanServicingCaseId").is(row.getLoanServicingCaseId()).and("installmentNumber").is(row.getInstallmentNumber())
                .and("dueDate").is(row.getDueDate()).and("principalDue").is(row.getPrincipalDue())
                .and("interestDue").is(row.getInterestDue()).and("feeDue").is(row.getFeeDue()).and("totalDue").is(row.getTotalDue())
                .and("remainingBalance").is(row.getRemainingBalance()).and("scheduleStatus").is(row.getScheduleStatus()).and("isPaid").is(false)
                .and("paidDate").isNull();
        return row.getPaidAmount() == null ? criteria.and("paidAmount").isNull() : criteria.and("paidAmount").is(row.getPaidAmount());
    }

    private <T> Mono<Void> insert(T row) {
        var strategy = template.getDataAccessStrategy();
        var mapper = strategy.getStatementMapper().forType(row.getClass());
        StatementMapper.InsertSpec insert = mapper.createInsert(strategy.getTableName(row.getClass()));
        var outbound = strategy.getOutboundRow(row);
        for (var column : outbound.keySet()) insert = insert.withColumn(column, outbound.get(column));
        return exactOne(template.getDatabaseClient().sql(mapper.getMappedObject(insert)).fetch().rowsUpdated());
    }

    private static Mono<Void> exactOne(Mono<Long> count) {
        return count.switchIfEmpty(Mono.error(stale())).flatMap(value -> value == 1 ? Mono.empty() : Mono.error(stale()));
    }

    private LoanRepaymentSchedule newRow(UUID caseId, AmortizationCalendarCalculator.Installment row) {
        var now = LocalDateTime.ofInstant(owner.clock().instant(), ZoneOffset.UTC);
        return LoanRepaymentSchedule.builder().loanRepaymentScheduleId(UUID.randomUUID()).loanServicingCaseId(caseId)
                .installmentNumber(row.number()).dueDate(row.dueDate()).principalDue(row.principalDue()).interestDue(row.interestDue())
                .feeDue(row.feeDue()).totalDue(row.totalDue()).remainingBalance(row.remainingBalance()).scheduleStatus(ScheduleStatus.CURRENT)
                .isPaid(false).createdAt(now).updatedAt(now).build();
    }

    private static List<LoanRepaymentSchedule> active(List<LoanRepaymentSchedule> rows) {
        return rows.stream().filter(row -> row.getScheduleStatus() != ScheduleStatus.SUPERSEDED).toList();
    }

    private static void requireUnpaid(LoanRepaymentSchedule row) {
        if (row.getScheduleStatus() == null || !Boolean.FALSE.equals(row.getIsPaid()) || row.getPaidDate() != null
                || row.getPaidAmount() != null && row.getPaidAmount().signum() != 0) throw stale();
    }

    private static void requireCompleteUnpaidSchedule(List<LoanRepaymentSchedule> rows) {
        if (rows.isEmpty() || rows.size() > 600) throw stale();
        BigDecimal remaining = BigDecimal.ZERO;
        for (var row : rows) {
            requireUnpaid(row);
            for (BigDecimal money : new BigDecimal[]{row.getPrincipalDue(), row.getInterestDue(), row.getFeeDue(), row.getTotalDue(), row.getRemainingBalance()}) {
                if (money == null || money.signum() < 0 || money.scale() > 2 || (long) money.precision() - money.scale() > 15) throw stale();
            }
            if (row.getFeeDue().signum() != 0 || row.getPrincipalDue().add(row.getInterestDue()).compareTo(row.getTotalDue()) != 0) throw stale();
            remaining = remaining.add(row.getPrincipalDue());
        }
        var first = rows.getFirst().getDueDate();
        if (first == null || first.getDayOfMonth() != 1) throw stale();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            remaining = remaining.subtract(row.getPrincipalDue());
            if (!Objects.equals(row.getInstallmentNumber(), index + 1) || !Objects.equals(row.getDueDate(), first.plusMonths(index))
                    || remaining.compareTo(row.getRemainingBalance()) != 0) throw stale();
        }
        if (remaining.signum() != 0) throw stale();
    }

    private static boolean matches(LoanRepaymentSchedule row, AmortizationCalendarCalculator.Installment expected) {
        return row.getInstallmentNumber() != null && row.getInstallmentNumber() == expected.number()
                && Objects.equals(row.getDueDate(), expected.dueDate()) && equal(row.getPrincipalDue(), expected.principalDue())
                && equal(row.getInterestDue(), expected.interestDue()) && equal(row.getFeeDue(), expected.feeDue())
                && equal(row.getTotalDue(), expected.totalDue()) && equal(row.getRemainingBalance(), expected.remainingBalance());
    }

    private static boolean equal(BigDecimal left, BigDecimal right) { return left != null && left.compareTo(right) == 0; }

    private static List<LoanRepaymentScheduleDTO> observedRows(List<LoanRepaymentSchedule> observed,
            List<LoanRepaymentSchedule> expected, ScheduleStatus status) {
        return expected.stream().map(before -> {
            var row = observed.stream().filter(value -> value.getLoanRepaymentScheduleId().equals(before.getLoanRepaymentScheduleId()))
                    .findFirst().orElseThrow(AuditedAmortizationCalendarService::stale);
            if (!row.getLoanServicingCaseId().equals(before.getLoanServicingCaseId()) || row.getScheduleStatus() != status
                    || !Objects.equals(row.getInstallmentNumber(), before.getInstallmentNumber()) || !Objects.equals(row.getDueDate(), before.getDueDate())
                    || !equal(row.getPrincipalDue(), before.getPrincipalDue()) || !equal(row.getInterestDue(), before.getInterestDue())
                    || !equal(row.getFeeDue(), before.getFeeDue()) || !equal(row.getTotalDue(), before.getTotalDue())
                    || !equal(row.getRemainingBalance(), before.getRemainingBalance())) throw stale();
            requireUnpaid(row);
            return dto(row, status);
        }).toList();
    }

    private static List<UUID> ids(List<UUID> ids) {
        List<UUID> copy = List.copyOf(ids);
        if (copy.isEmpty() || copy.size() > 600 || new HashSet<>(copy).size() != copy.size()
                || copy.contains(new UUID(0, 0))) throw stale();
        return copy;
    }

    private static String effectDigest(String action, UUID caseId, String intent, LoanServicingCase loan,
            List<LoanRepaymentSchedule> before, List<LoanRepaymentSchedule> after, Optional<CalendarRevision> revision) {
        var fields = new ArrayList<String>();
        fields.add("LENDING_CALENDAR_EFFECT_V1"); fields.add(action); fields.add(caseId.toString()); fields.add(intent);
        fields.add(revision.map(row -> Long.toString(row.revision())).orElse("UNADOPTED"));
        if (loan != null) fields.add(String.join("|", String.valueOf(loan.getContractId()), String.valueOf(loan.getProductId()),
                String.valueOf(loan.getApplicationId()), String.valueOf(loan.getProposedOfferId()), String.valueOf(loan.getServicingStatus()),
                String.valueOf(loan.getPrincipalAmount()), String.valueOf(loan.getInterestRate()), String.valueOf(loan.getLoanTerm()),
                String.valueOf(loan.getInterestCalculationMethod()), String.valueOf(loan.getAmortizationMethod()),
                String.valueOf(loan.getPaymentFrequency()), String.valueOf(loan.getCompoundingFrequency()),
                String.valueOf(loan.getDayCountConvention()), String.valueOf(loan.getOriginationDate()), String.valueOf(loan.getMaturityDate())));
        for (var rows : List.of(before, after)) {
            fields.add("ROWS:" + rows.size());
            for (var row : rows) fields.add(String.join("|", row.getLoanRepaymentScheduleId().toString(), String.valueOf(row.getInstallmentNumber()),
                    String.valueOf(row.getDueDate()), String.valueOf(row.getPrincipalDue()), String.valueOf(row.getInterestDue()),
                    String.valueOf(row.getFeeDue()), String.valueOf(row.getTotalDue()), String.valueOf(row.getRemainingBalance()),
                    String.valueOf(row.getScheduleStatus()), String.valueOf(row.getIsPaid()), String.valueOf(row.getPaidDate()), String.valueOf(row.getPaidAmount())));
        }
        return PeriodicCalendarIntent.digest(String.join("\n", fields));
    }

    private static LoanRepaymentScheduleDTO dto(LoanRepaymentSchedule row, ScheduleStatus status) {
        return LoanRepaymentScheduleDTO.builder().loanRepaymentScheduleId(row.getLoanRepaymentScheduleId())
                .loanServicingCaseId(row.getLoanServicingCaseId()).installmentNumber(row.getInstallmentNumber()).dueDate(row.getDueDate())
                .principalDue(row.getPrincipalDue()).interestDue(row.getInterestDue()).feeDue(row.getFeeDue()).totalDue(row.getTotalDue())
                .remainingBalance(row.getRemainingBalance()).scheduleStatus(status).isPaid(row.getIsPaid()).paidDate(row.getPaidDate())
                .paidAmount(row.getPaidAmount()).createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt()).build();
    }

    private void requireOwner() {
        if (owner == null) throw new ServiceUnavailableException("CALENDAR_OWNER_UNAVAILABLE",
                "Paired durable calendar audit resources required", "loan-repayment-calendar", null, null);
    }
    private static OptimisticLockingFailureException stale() { return new OptimisticLockingFailureException("Exact current unpaid calendar and case scope required"); }
}
