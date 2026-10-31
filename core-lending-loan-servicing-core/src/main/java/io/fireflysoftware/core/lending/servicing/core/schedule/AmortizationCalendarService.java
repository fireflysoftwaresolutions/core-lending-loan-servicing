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

import io.fireflysoftware.core.lending.servicing.interfaces.dtos.*;
import io.fireflysoftware.core.lending.servicing.models.repositories.LoanRepaymentScheduleRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Legacy direct construction cannot supply the owner resources required by calendar operations. */
@Deprecated
public class AmortizationCalendarService {
    public AmortizationCalendarService(LoanRepaymentScheduleRepository schedules) {
        Objects.requireNonNull(schedules);
    }

    public Mono<List<LoanRepaymentScheduleDTO>> store(UUID caseId, StoreAmortizationCalendarRequest request) {
        return unavailable();
    }
    public Mono<List<LoanRepaymentScheduleDTO>> read(UUID caseId, boolean includeSuperseded) {
        return unavailable();
    }
    public Mono<ScheduleRevisionDTO> replace(UUID caseId, StoreAmortizationCalendarRequest request) {
        return unavailable();
    }
    public Mono<List<LoanRepaymentScheduleDTO>> restore(UUID caseId, RestoreScheduleRequest request) {
        return unavailable();
    }
    private static <T> Mono<T> unavailable() {
        return Mono.error(new IllegalStateException("Use the owner-installed audited calendar operations"));
    }
}
