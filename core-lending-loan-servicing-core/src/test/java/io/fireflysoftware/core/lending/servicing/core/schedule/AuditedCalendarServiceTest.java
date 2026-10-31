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

import io.fireflysoftware.core.lending.servicing.core.schedule.impl.AuditedAmortizationCalendarService;
import io.fireflysoftware.core.lending.servicing.interfaces.dtos.*;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.*;
import io.fireflysoftware.core.lending.servicing.models.entities.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.fireflyframework.core.audit.AuditAppendAuthority;
import org.fireflyframework.core.transaction.CleanupPolicy;
import org.fireflyframework.kernel.audit.AuditActor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class AuditedCalendarServiceTest {
    static final class Fixture {
        final CalendarR2dbcFixture database = new CalendarR2dbcFixture();
        final UUID caseId = UUID.randomUUID();
        final CalendarAuditAdmission.Permit permit = new CalendarAuditAdmission.Permit(caseId, UUID.randomUUID(),
                new AuditActor("https://issuer.owned.invalid", "opaque subject", "owned-client", "opaque platform tenant", null, null),
                UUID.randomUUID(), 1, "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "calendar-policy-v1", Instant.now().plusSeconds(60));
        boolean authorityAvailable = true;
        boolean appendAvailable = true;
        java.util.function.Supplier<CalendarAuditAdmission.Permit> currentPermit = () -> permit;
        final AuditedCalendarResources owner = new AuditedCalendarResources(database.resources, database.client, database.template,
                database.manager, new CleanupPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                event -> appendAvailable ? Mono.just(new AuditAppendAuthority.Permit(event.stream(), event.actor(), permit.notAfter())) : Mono.empty(),
                (id, action, digest) -> Mono.defer(() -> authorityAvailable && caseId.equals(id) ? Mono.just(currentPermit.get()) : Mono.empty()), database.clock, database.nanos);
        final AmortizationCalendarOperations service = new AuditedAmortizationCalendarService(owner);

        void terms(String principal, String rate, int term, LocalDate first) {
            var row = LoanServicingCase.builder().loanServicingCaseId(caseId).contractId(UUID.randomUUID()).productId(UUID.randomUUID())
                    .applicationId(UUID.randomUUID()).productCatalogId(UUID.randomUUID()).servicingStatus(ServicingStatusEnum.PENDING)
                    .principalAmount(new BigDecimal(principal)).interestRate(new BigDecimal(rate)).loanTerm(term)
                    .interestCalculationMethod(InterestCalculationMethodEnum.REDUCING_BALANCE).amortizationMethod(AmortizationMethodEnum.EQUAL_INSTALLMENT)
                    .paymentFrequency(PaymentFrequencyEnum.MONTHLY).compoundingFrequency(CompoundingFrequencyEnum.MONTHLY)
                    .dayCountConvention(DayCountConventionEnum.THIRTY_360).originationDate(first.minusMonths(1))
                    .maturityDate(first.minusMonths(1).plusMonths(term)).build();
            var mapped = new LinkedHashMap<String,Object>();
            var outbound = database.template.getDataAccessStrategy().getOutboundRow(row);
            for (var column : outbound.keySet()) mapped.put(column.getReference(), outbound.get(column).getValue());
            database.driver.tables.put("loan_servicing_case", new ArrayList<>(List.of(mapped)));
        }

        Map<UUID,LoanRepaymentSchedule> rows() {
            var result = new LinkedHashMap<UUID,LoanRepaymentSchedule>();
            for (var row : database.driver.tables.getOrDefault("loan_repayment_schedule", List.of())) {
                var entity = database.template.getConverter().read(LoanRepaymentSchedule.class, database.driver.row(row), database.driver.metadata(row));
                result.put(entity.getLoanRepaymentScheduleId(), entity);
            }
            return result;
        }
    }

    static StoreAmortizationCalendarRequest request(String principal, String rate, int term, LocalDate first) {
        return new StoreAmortizationCalendarRequest(new BigDecimal(principal), new BigDecimal(rate), term,
                PaymentFrequencyEnum.MONTHLY, AmortizationMethodEnum.EQUAL_INSTALLMENT, first, null);
    }

    @Test void actualMappedCalendarAndCanonicalAppenderCompleteOnOneOwnedLease() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        var result = f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).block();
        assertThat(result).hasSize(12);
        assertThat(f.database.driver.creates).isEqualTo(1);
        assertThat(f.database.driver.commits).isEqualTo(1);
        assertThat(f.database.driver.closes).isEqualTo(1);
        assertThat(f.database.driver.nativeEnters).isEqualTo(1);
        assertThat(f.database.driver.nativeCompletions).isEqualTo(1);
        for (String table : List.of("audit_event", "audit_outbox", "loan_calendar_revision")) assertThat(f.database.driver.tables.get(table)).hasSize(1);
        assertThat(f.rows()).hasSize(12);
        assertThat(f.database.driver.tables.get("loan_calendar_revision").getFirst().get("revision")).isEqualTo(0L);
        assertThat(result.getFirst().getInterestDue()).isEqualByComparingTo("12.00");
        assertThat(result.getLast().getRemainingBalance()).isEqualByComparingTo("0.00");
        assertThat(f.database.driver.sql).anySatisfy(sql -> assertThat(sql).contains("FOR UPDATE"));
    }

    @Test void failedSecondReplacementInsertRollsBackPreviousRowsNewRowsAndAudit() {
        var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
        f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
        f.terms("600.00", "6.00", 2, first.plusMonths(1));
        var before = f.database.driver.copy();
        f.database.driver.failWrite = f.database.driver.writes + 9;
        assertThatThrownBy(() -> f.service.replace(f.caseId, request("600.00", "6.00", 2, first.plusMonths(1))).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.writes).isEqualTo(f.database.driver.failWrite);
        assertThat(f.database.driver.tables).isEqualTo(before);
        assertThat(f.database.driver.rollbacks).isEqualTo(1);
        assertThat(f.database.driver.commits).isEqualTo(1);
        assertThat(f.rows().values()).allSatisfy(row -> assertThat(row.getScheduleStatus()).isEqualTo(ScheduleStatus.CURRENT));
    }

    @Test void zeroRevisionCasCannotEmitOrCommitAReplacement() {
        var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
        f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
        var before = f.database.driver.copy();
        f.database.driver.countAtWrite = f.database.driver.writes + 4;
        f.database.driver.writeCount = 0;
        assertThatThrownBy(() -> f.service.replace(f.caseId, request("900.00", "12.00", 3, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.tables).isEqualTo(before);
        assertThat(f.database.driver.writes).isEqualTo(f.database.driver.countAtWrite);
        assertThat(f.database.driver.sql).anySatisfy(sql -> assertThat(sql).contains("loan_calendar_revision").contains("\"revision\" ="));
    }

    @Test void missingOwnerRefusesBeforeAnyCalendarAccess() {
        var service = new AuditedAmortizationCalendarService((AuditedCalendarResources) null);
        assertThatThrownBy(() -> service.store(UUID.randomUUID(), request("1200.00", "12.00", 12, LocalDate.of(2026, 2, 1))).block())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void actualMissingCaseCannotAppendOrWrite() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "12.00", 12, LocalDate.of(2026, 2, 1))).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.writes).isZero();
        assertThat(f.database.driver.nativeEnters).isZero();
    }

    @Test void persistedTermsMismatchCannotChangeTheCalendar() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "6.00", 12, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.writes).isZero();
        assertThat(f.database.driver.nativeEnters).isZero();
    }

    @Test void invalidReplacementNeverSupersedesExistingRows() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).block();
        var before = f.database.driver.copy(); int creates = f.database.driver.creates;
        assertThatThrownBy(() -> f.service.replace(f.caseId, request("0.00", "12.00", 12, first)).block()).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.database.driver.tables).isEqualTo(before);
        assertThat(f.database.driver.creates).isEqualTo(creates);
    }

    @Test void missingTrustedAuthorityRefusesBeforeAcquiringALease() {
        var f = new Fixture(); f.authorityAvailable = false;
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "12.00", 12, LocalDate.of(2026, 2, 1))).block()).isInstanceOf(SecurityException.class);
        assertThat(f.database.driver.creates).isZero();
        assertThat(f.database.driver.sql).isEmpty();
    }

    @Test void authorityLostAfterCaseReadCannotAppendOrWrite() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        f.database.driver.beforeRead = () -> f.authorityAvailable = false;
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.selects).isPositive();
        assertThat(f.database.driver.writes).isZero();
        assertThat(f.database.driver.nativeEnters).isZero();
        assertThat(f.database.driver.rollbacks).isEqualTo(1);
    }

    @Test void laterPermitCannotRenewTheCapturedAuthorityWindow() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        var p = f.permit;
        f.database.driver.beforeRead = () -> f.currentPermit = () -> new CalendarAuditAdmission.Permit(p.caseId(), p.resourceTenantId(),
                p.actor(), p.bindingId(), p.bindingVersion(), p.bindingDigest(), p.processId(), p.correlationId(), p.policyRevision(), p.notAfter().plusSeconds(5));
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.writes).isZero();
        assertThat(f.database.driver.nativeEnters).isZero();
    }

    @Test void unavailableActualAppenderAuthorityCannotWriteTheCalendar() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first); f.appendAvailable = false;
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.writes).isZero();
        assertThat(f.database.driver.nativeEnters).isZero();
        assertThat(f.database.driver.rollbacks).isEqualTo(1);
    }

    @Test void responseWaitsForActualCommitAndCloseCompletion() throws Exception {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        f.database.driver.commitGate = reactor.core.publisher.Sinks.empty();
        f.database.driver.closeGate = reactor.core.publisher.Sinks.empty();
        var result = new java.util.concurrent.atomic.AtomicReference<List<LoanRepaymentScheduleDTO>>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        var subscription = f.service.store(f.caseId, request("1200.00", "12.00", 12, first))
                .subscribe(result::set, error -> { failure.set(error); done.countDown(); }, done::countDown);
        try {
            assertThat(f.database.driver.commitEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(result).hasNullValue();
            assertThat(f.database.driver.closes).isZero();
            f.database.driver.commitGate.tryEmitEmpty();
            assertThat(f.database.driver.closeEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(result).hasNullValue();
            f.database.driver.closeGate.tryEmitEmpty();
            assertThat(done.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(failure).hasNullValue();
            assertThat(result.get()).hasSize(12);
            assertThat(f.database.driver.closes).isEqualTo(1);
        } finally {
            f.database.driver.commitGate.tryEmitEmpty(); f.database.driver.closeGate.tryEmitEmpty(); subscription.dispose();
        }
    }

    @Test void failedOwnedCloseCannotPublishAnOtherwiseCommittedResult() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        f.database.driver.closeFailure = new IllegalStateException("controlled close refusal");
        assertThatThrownBy(() -> f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.commits).isEqualTo(1);
        assertThat(f.database.driver.closes).isPositive();
    }

    @Test void ordinaryCaseMappingDoesNotQueryOrWriteAnOptionalCalendarColumn() {
        var f = new Fixture(); f.terms("1200.00", "12.00", 12, LocalDate.of(2026, 2, 1));
        var standard = new org.springframework.data.r2dbc.core.R2dbcEntityTemplate(
                org.springframework.r2dbc.core.DatabaseClient.create(f.database.driver), org.springframework.data.r2dbc.dialect.PostgresDialect.INSTANCE);
        var repository = new org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory(standard)
                .getRepository(io.fireflysoftware.core.lending.servicing.models.repositories.LoanServicingCaseRepository.class);
        var persisted = repository.findById(f.caseId).block();
        assertThat(persisted).isNotNull();
        repository.save(persisted).block();
        assertThat(f.database.driver.tables).doesNotContainKey("loan_calendar_revision");
        assertThat(f.database.driver.sql).noneMatch(sql -> sql.contains("calendar_revision") || sql.contains("calendar_version"));
    }

    @Test void cancellationWhileWaitingForTheCaseClosesOnlyTheOwnedLease() throws Exception {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        f.database.driver.caseGate = reactor.core.publisher.Sinks.empty();
        var emitted = new java.util.concurrent.atomic.AtomicBoolean();
        var subscription = f.service.store(f.caseId, request("1200.00", "12.00", 12, first)).subscribe(ignored -> emitted.set(true), ignored -> { });
        try {
            assertThat(f.database.driver.caseEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(f.database.driver.closeEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(emitted).isFalse();
            assertThat(f.database.driver.rollbacks).isEqualTo(1);
            assertThat(f.database.driver.closes).isEqualTo(1);
            assertThat(f.database.driver.writes).isZero();
            assertThat(f.database.driver.nativeEnters).isZero();
            assertThat(f.database.driver.caseGate.currentSubscriberCount()).isZero();
        } finally { subscription.dispose(); f.database.driver.caseGate.tryEmitEmpty(); }
    }

    @Test void callerDtoMutationAfterInvocationCannotChangeTheWireOrResult() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("1200.00", "12.00", 12, first);
        var request = request("1200.00", "12.00", 12, first);
        var operation = f.service.store(f.caseId, request);
        request.setPrincipal(BigDecimal.ZERO); request.setTerm(1); request.setAnnualRatePercent(BigDecimal.ZERO);
        var result = operation.block();
        assertThat(result).hasSize(12);
        assertThat(result.stream().map(LoanRepaymentScheduleDTO::getPrincipalDue).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1200.00");
        assertThat(f.rows()).hasSize(12);
        assertThat(f.rows().values().stream().map(LoanRepaymentSchedule::getPrincipalDue).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1200.00");
    }

    @Test void restoreCapturesCallerOwnedIdentityListsBeforeSubscription() {
        var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
        var old = f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
        f.terms("600.00", "6.00", 2, first.plusMonths(1));
        var replacement = f.service.replace(f.caseId, request("600.00", "6.00", 2, first.plusMonths(1))).block();
        f.terms("900.00", "12.00", 3, first);
        var previousIds = new ArrayList<>(old.stream().map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId).toList());
        var replacementIds = new ArrayList<>(replacement.getCurrent().stream().map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId).toList());
        var operation = f.service.restore(f.caseId, new RestoreScheduleRequest(previousIds, replacementIds));
        previousIds.clear(); replacementIds.clear();
        assertThat(operation.block()).extracting(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId)
                .containsExactlyElementsOf(old.stream().map(LoanRepaymentScheduleDTO::getLoanRepaymentScheduleId).toList());
        assertThat(f.rows()).hasSize(3);
    }

    @Test void unadoptedLegacyNullBalanceRefusesBeforeAnotherAuditAppend() {
        var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
        f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
        f.database.driver.tables.get("loan_repayment_schedule").getFirst().put("remaining_balance", null);
        var before = f.database.driver.copy(); int enters = f.database.driver.nativeEnters;
        assertThatThrownBy(() -> f.service.replace(f.caseId, request("900.00", "12.00", 3, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.nativeEnters).isEqualTo(enters);
        assertThat(f.database.driver.tables).isEqualTo(before);
    }

    @Test void returnedRevisionUsesActualMappedSupersededMetadata() {
        var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
        f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
        f.terms("600.00", "6.00", 2, first.plusMonths(1));
        var revision = f.service.replace(f.caseId, request("600.00", "6.00", 2, first.plusMonths(1))).block();
        var stored = f.service.read(f.caseId, true).block();
        for (var returned : revision.getPrevious()) {
            var mapped = stored.stream().filter(row -> row.getLoanRepaymentScheduleId().equals(returned.getLoanRepaymentScheduleId())).findFirst().orElseThrow();
            assertThat(returned.getUpdatedAt()).isEqualTo(mapped.getUpdatedAt());
            assertThat(returned.getScheduleStatus()).isEqualTo(mapped.getScheduleStatus());
        }
    }

    @Test void missingOrMultipleRevisionCountsCannotPublishAReplacement() {
        for (boolean empty : new boolean[]{false, true}) {
            var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
            f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
            var before = f.database.driver.copy();
            f.database.driver.countAtWrite = f.database.driver.writes + 4;
            f.database.driver.writeCount = empty ? 1 : 2;
            f.database.driver.emptyCount = empty;
            assertThatThrownBy(() -> f.service.replace(f.caseId, request("900.00", "12.00", 3, first)).block()).isInstanceOf(RuntimeException.class);
            assertThat(f.database.driver.writes).isEqualTo(f.database.driver.countAtWrite);
            assertThat(f.database.driver.tables).isEqualTo(before);
        }
    }

    @Test void exhaustedGenerationCannotResetOrOverflow() {
        var f = new Fixture(); var first = LocalDate.of(2026, 3, 1); f.terms("900.00", "12.00", 3, first);
        f.service.store(f.caseId, request("900.00", "12.00", 3, first)).block();
        f.database.driver.tables.get("loan_calendar_revision").getFirst().put("revision", Long.MAX_VALUE);
        var before = f.database.driver.copy(); int enters = f.database.driver.nativeEnters;
        assertThatThrownBy(() -> f.service.replace(f.caseId, request("900.00", "12.00", 3, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.tables).isEqualTo(before);
        assertThat(f.database.driver.nativeEnters).isEqualTo(enters);
    }

    @Test void aDifferentTemplateCannotBeAdoptedByTheOwnerGraph() {
        var f = new Fixture(); var other = new CalendarR2dbcFixture();
        assertThatThrownBy(() -> new AuditedCalendarResources(f.database.resources, f.database.client, other.template,
                f.database.manager, new CleanupPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                event -> Mono.empty(), (id, action, digest) -> Mono.empty(), f.database.clock, f.database.nanos)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.database.driver.creates).isZero();
        assertThat(other.driver.creates).isZero();
    }

    @Test void defaultContextRoutesTheControllerOnlyThroughTheFunctionalAuditedBean() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var business = org.mockito.Mockito.mock(io.r2dbc.spi.ConnectionFactory.class);
            context.registerBean(io.r2dbc.spi.ConnectionFactory.class, () -> business);
            context.registerBean(io.fireflysoftware.core.lending.servicing.core.services.LoanRepaymentScheduleService.class,
                    () -> org.mockito.Mockito.mock(io.fireflysoftware.core.lending.servicing.core.services.LoanRepaymentScheduleService.class));
            context.scan("io.fireflysoftware.core.lending.servicing.core.schedule");
            context.register(io.fireflysoftware.core.lending.servicing.web.controllers.LoanRepaymentScheduleController.class);
            context.refresh();
            assertThat(context.getBeansOfType(AmortizationCalendarOperations.class)).hasSize(1);
            assertThat(context.containsBean("amortizationCalendarService")).isFalse();
            var controller = context.getBean(io.fireflysoftware.core.lending.servicing.web.controllers.LoanRepaymentScheduleController.class);
            assertThatThrownBy(() -> controller.storeCalendar(UUID.randomUUID(), request("1200.00", "12.00", 12, LocalDate.of(2026, 2, 1))).block()).isInstanceOf(IllegalStateException.class);
            org.mockito.Mockito.verifyNoInteractions(business);
        }
    }

    @Test void maximumPeriodCalendarsRespectTheRetainedHistoryBoundBeforeWriting() {
        var f = new Fixture(); var first = LocalDate.of(2026, 2, 1); f.terms("60000.00", "0.00", 600, first);
        assertThat(f.service.store(f.caseId, request("60000.00", "0.00", 600, first)).block()).hasSize(600);
        assertThat(f.service.replace(f.caseId, request("60000.00", "0.00", 600, first)).block().getCurrent()).hasSize(600);
        assertThat(f.rows()).hasSize(1200);
        int writes = f.database.driver.writes, enters = f.database.driver.nativeEnters, statements = f.database.driver.sql.size();
        assertThatThrownBy(() -> f.service.replace(f.caseId, request("60000.00", "0.00", 600, first)).block()).isInstanceOf(RuntimeException.class);
        assertThat(f.database.driver.sql.subList(statements, f.database.driver.sql.size())).hasSize(2)
                .anySatisfy(sql -> assertThat(sql).contains("loan_servicing_case").contains("FOR UPDATE"))
                .anySatisfy(sql -> assertThat(sql).contains("loan_repayment_schedule"));
        assertThat(f.database.driver.writes).isEqualTo(writes);
        assertThat(f.database.driver.nativeEnters).isEqualTo(enters);
        assertThat(f.rows()).hasSize(1200);
    }

    @Test void longerAuthorityCannotExtendTheOriginalFiveSecondOperation() {
        var ticks = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        var database = new CalendarR2dbcFixture(Clock.systemUTC(), ticks::get);
        UUID caseId = UUID.randomUUID();
        var permit = new CalendarAuditAdmission.Permit(caseId, UUID.randomUUID(),
                new AuditActor("https://issuer.owned.invalid", "opaque subject", "owned-client", "opaque platform tenant", null, null),
                UUID.randomUUID(), 1, "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "calendar-policy-v1", Instant.now().plusSeconds(60));
        var resources = new AuditedCalendarResources(database.resources, database.client, database.template, database.manager,
                new CleanupPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                event -> Mono.just(new AuditAppendAuthority.Permit(event.stream(), event.actor(), permit.notAfter())),
                (id, action, digest) -> Mono.just(permit), database.clock, database.nanos);
        assertThatThrownBy(() -> resources.execute(caseId, "lending.calendar.store", "a".repeat(64), ignored -> {
            ticks.addAndGet(Duration.ofSeconds(6).toNanos());
            return Mono.just(new AuditedCalendarResources.Plan<>("b".repeat(64), record -> Mono.just("must-not-emit")));
        }).block()).isInstanceOf(RuntimeException.class);
        assertThat(database.driver.nativeEnters).isZero();
        assertThat(database.driver.writes).isZero();
        assertThat(database.driver.rollbacks).isEqualTo(1);
        assertThat(database.driver.closes).isEqualTo(1);
    }
}
