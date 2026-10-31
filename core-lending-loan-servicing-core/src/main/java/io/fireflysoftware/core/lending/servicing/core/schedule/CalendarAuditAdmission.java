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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.fireflyframework.kernel.audit.AuditActor;
import reactor.core.publisher.Mono;

/** Owner-installed authorization binds opaque platform identity to the exact resource case. */
@FunctionalInterface
public interface CalendarAuditAdmission {
    record Permit(UUID caseId, UUID resourceTenantId, AuditActor actor, UUID bindingId,
                  long bindingVersion, String bindingDigest, UUID processId, UUID correlationId,
                  String policyRevision, Instant notAfter) {
        public Permit {
            for (UUID id : new UUID[]{caseId, resourceTenantId, bindingId, processId, correlationId}) {
                if (id == null || id.equals(new UUID(0, 0))) throw new IllegalArgumentException("Exact calendar ownership required");
            }
            Objects.requireNonNull(actor);
            Objects.requireNonNull(notAfter);
            if (bindingVersion < 1 || bindingDigest == null || !bindingDigest.matches("[0-9a-f]{64}")
                    || policyRevision == null || !policyRevision.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
                throw new IllegalArgumentException("Exact calendar authority required");
            }
        }

        @Override public String toString() { return "CalendarPermit[redacted]"; }
    }

    Mono<Permit> requireCurrent(UUID caseId, String action, String intentDigest);
}
