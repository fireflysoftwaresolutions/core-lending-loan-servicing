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
package io.fireflysoftware.core.lending.servicing.models.repositories.calendar;

import io.fireflysoftware.core.lending.servicing.models.entities.LoanServicingCase;
import java.util.UUID;
import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/** Constructed from the exact owner template, never an independently scanned business pool. */
@NoRepositoryBean
public interface CalendarCaseRepository extends ReactiveCrudRepository<LoanServicingCase, UUID> {
    @Lock(LockMode.PESSIMISTIC_WRITE)
    Mono<LoanServicingCase> findByLoanServicingCaseId(UUID caseId);
}
