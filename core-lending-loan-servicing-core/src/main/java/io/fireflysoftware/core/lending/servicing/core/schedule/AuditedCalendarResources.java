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

import java.time.*;
import java.util.Objects;
import java.util.UUID;
import java.util.function.*;
import org.fireflyframework.core.audit.*;
import org.fireflyframework.core.audit.impl.SpringDataTransactionalAuditAppender;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction;
import org.fireflyframework.kernel.audit.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

/** Explicit owner-installed graph; there is no unaudited or ambient-transaction fallback. */
public final class AuditedCalendarResources {
    public record Plan<T>(String effectDigest, Function<AuditRecord, Mono<T>> writes) {
        public Plan {
            if (effectDigest == null || !effectDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Calendar evidence digest required");
            Objects.requireNonNull(writes);
        }
    }

    private final BoundedR2dbcResources resources;
    private final R2dbcEntityTemplate template;
    private final BoundedRootTransaction root;
    private final TransactionalAuditAppender append;
    private final CalendarAuditAdmission admission;
    private final Clock clock;
    private final LongSupplier nanos;

    public AuditedCalendarResources(BoundedR2dbcResources resources, DatabaseClient client,
            R2dbcEntityTemplate template, R2dbcTransactionManager manager, CleanupPolicy cleanup,
            AuditAppendAuthority appendAuthority, CalendarAuditAdmission admission, Clock clock, LongSupplier nanos) {
        this.resources = BoundedR2dbcResources.verify(Objects.requireNonNull(resources).factory(), client, template, manager);
        this.template = template;
        this.root = new DefaultBoundedRootTransaction(this.resources, Objects.requireNonNull(cleanup));
        this.admission = Objects.requireNonNull(admission);
        this.clock = Objects.requireNonNull(clock);
        this.nanos = Objects.requireNonNull(nanos);
        this.append = new SpringDataTransactionalAuditAppender(this.resources, client, template, manager,
                Objects.requireNonNull(appendAuthority), clock, nanos, UUID::randomUUID);
    }

    public R2dbcEntityTemplate template() { return template; }
    public Clock clock() { return clock; }

    public <T> Mono<T> execute(UUID caseId, String action, String intentDigest,
            Function<CalendarAuditAdmission.Permit, Mono<Plan<T>>> preparation) {
        return Mono.defer(() -> {
            if (caseId == null || caseId.equals(new UUID(0, 0))) return Mono.error(denied());
            if (!java.util.Set.of("lending.calendar.store", "lending.calendar.replace", "lending.calendar.restore", "lending.calendar.read").contains(action)
                    || intentDigest == null || !intentDigest.matches("[0-9a-f]{64}")) return Mono.error(denied());
            Instant started = clock.instant();
            long startedNanos = nanos.getAsLong();
            Instant deadline = started.plusSeconds(5);
            var original = R2dbcOperationBudget.observed(started, deadline, startedNanos, Duration.ofSeconds(5), clock, nanos);
            return Mono.defer(() -> admission.requireCurrent(caseId, action, intentDigest))
                    .switchIfEmpty(Mono.error(denied())).timeout(original.remaining())
                    .flatMap(permit -> {
                        if (!caseId.equals(permit.caseId()) || !clock.instant().isBefore(permit.notAfter())) return Mono.error(denied());
                        Instant until = permit.notAfter().isBefore(deadline) ? permit.notAfter() : deadline;
                        var budget = R2dbcOperationBudget.observed(started, until, startedNanos, Duration.ofSeconds(5), clock, nanos);
                        var definition = new DefaultTransactionDefinition();
                        definition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                        definition.setReadOnly(false);
                        return root.execute(budget, definition, scope ->
                                resources.factory().requireBoundScope(resources, scope)
                                .then(current(permit, caseId, action, intentDigest, scope))
                                .then(Mono.defer(() -> preparation.apply(permit))).timeout(scope.remaining()).switchIfEmpty(Mono.error(failed()))
                                .flatMap(plan -> current(permit, caseId, action, intentDigest, scope).then(Mono.defer(() -> {
                                    String boundEvidence = PeriodicCalendarIntent.digest(String.join("\n", "LENDING_CALENDAR_AUTHORITY_V1",
                                            plan.effectDigest(), permit.resourceTenantId().toString(), permit.bindingId().toString(),
                                            Long.toString(permit.bindingVersion()), permit.bindingDigest(), permit.notAfter().toString()));
                                    var event = new AuditEvent(UUID.randomUUID(),
                                            new AuditStream(permit.resourceTenantId(), "core-loan-servicing", "repayment-calendar", caseId),
                                            permit.actor(), action, AuditEvent.Outcome.COMPLETED, permit.processId(),
                                            permit.correlationId(), null, clock.instant(), boundEvidence, permit.policyRevision());
                                    return append.append(scope, event).switchIfEmpty(Mono.error(failed())).timeout(scope.remaining())
                                            .flatMap(provisional -> {
                                                if (provisional.replay() || provisional.record() == null || !event.equals(provisional.record().event())) return Mono.error(failed());
                                                AuditRecord record = provisional.record();
                                                var previous = new AuditCheckpoint(event.stream(), record.sequence() - 1, record.previousHash());
                                                if (!record.equals(AuditRecordHasher.record(previous, event, record.recordedAt()))) return Mono.error(failed());
                                                return current(permit, caseId, action, intentDigest, scope)
                                                        .then(resources.factory().requireBoundScope(resources, scope))
                                                        .then(Mono.defer(() -> plan.writes().apply(record)))
                                                        .switchIfEmpty(Mono.error(failed())).timeout(scope.remaining())
                                                        .flatMap(value -> current(permit, caseId, action, intentDigest, scope).thenReturn(value));
                                            });
                                }))).onErrorResume(error -> resources.factory().markBoundScopeRollbackOnly(resources, scope)
                                        .onErrorResume(markFailure -> Mono.error(error)).then(Mono.error(error))))
                                .map(completed -> {
                                    var facts = completed.transactionFacts();
                                    if (!facts.nativeAttempted() || !facts.commitAcknowledged() || !facts.boundaryCompleted()
                                            || facts.rollbackAcknowledged() || completed.cleanupFact() != CleanupFact.COMPLETE) throw failed();
                                    if (!clock.instant().isBefore(until)) throw denied();
                                    return completed.value();
                                });
                    });
        });
    }

    private Mono<Void> current(CalendarAuditAdmission.Permit original, UUID caseId, String action,
            String digest, RootTransactionScope scope) {
        return Mono.defer(() -> {
            if (!clock.instant().isBefore(original.notAfter())) return Mono.error(denied());
            return admission.requireCurrent(caseId, action, digest).switchIfEmpty(Mono.error(denied()))
                    .timeout(scope.remaining()).flatMap(now -> original.equals(now) ? Mono.empty() : Mono.error(denied()));
        });
    }

    private static SecurityException denied() { return new SecurityException("Current trusted calendar ownership required"); }
    private static IllegalStateException failed() { return new IllegalStateException("Paired durable calendar audit and completed root required"); }
}
