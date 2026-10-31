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
package io.fireflysoftware.core.lending.servicing.web.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.fireflysoftware.core.lending.servicing.core.schedule.*;
import io.fireflysoftware.core.lending.servicing.core.schedule.impl.AuditedAmortizationCalendarService;
import io.fireflysoftware.core.lending.servicing.core.services.LoanRepaymentScheduleService;
import io.fireflysoftware.core.lending.servicing.interfaces.dtos.*;
import io.fireflysoftware.core.lending.servicing.interfaces.enums.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fireflyframework.web.error.config.ErrorHandlingProperties;
import org.fireflyframework.web.error.converter.*;
import org.fireflyframework.web.error.exceptions.ServiceUnavailableException;
import org.fireflyframework.web.error.handler.GlobalExceptionHandler;
import org.fireflyframework.web.error.service.ErrorResponseNegotiator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalendarHttpRefusalTest {
    private static final UUID CASE = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final String PATH = "/api/v1/loan-servicing-cases/" + CASE + "/repayment-schedules/calendar";

    private static StoreAmortizationCalendarRequest request() {
        return new StoreAmortizationCalendarRequest(new BigDecimal("1200.00"), new BigDecimal("12.00"), 12,
                PaymentFrequencyEnum.MONTHLY, AmortizationMethodEnum.EQUAL_INSTALLMENT, LocalDate.of(2026, 2, 1), null);
    }

    private static final class HttpFixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final io.r2dbc.spi.ConnectionFactory business = mock(io.r2dbc.spi.ConnectionFactory.class);
        final LoanRepaymentScheduleService ordinary = mock(LoanRepaymentScheduleService.class);
        final WebTestClient client;
        HttpFixture() {
            context.registerBean(io.r2dbc.spi.ConnectionFactory.class, () -> business);
            context.registerBean(LoanRepaymentScheduleService.class, () -> ordinary);
            context.scan("io.fireflysoftware.core.lending.servicing.core.schedule");
            context.register(LoanRepaymentScheduleController.class, DelegatingWebFluxConfiguration.class);
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            var properties = new ErrorHandlingProperties();
            context.registerBean(GlobalExceptionHandler.class, () -> new GlobalExceptionHandler(
                    new ExceptionConverterService(List.of(new ValidationExceptionConverter(), new WebFluxExceptionConverter())),
                    Optional.empty(), properties, Optional.empty(), Optional.empty(), new MockEnvironment(), mapper,
                    new ErrorResponseNegotiator(mapper), Optional.empty()));
            context.refresh();
            client = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build();
        }
        @Override public void close() { verifyNoInteractions(business, ordinary); context.close(); }
    }

    @Test void defaultContextRoutesTheControllerOnlyThroughTheFunctionalAuditedBean() {
        try (var f = new HttpFixture()) {
            assertThat(f.context.getBeansOfType(AmortizationCalendarOperations.class)).hasSize(1);
            assertThat(f.context.containsBean("amortizationCalendarService")).isFalse();
            var controller = f.context.getBean(LoanRepaymentScheduleController.class);
            assertThatThrownBy(() -> controller.storeCalendar(CASE, request()).block()).isInstanceOf(ServiceUnavailableException.class);
        }
    }

    @Test void missingOwnerStoreReturnsTyped503WithoutBusinessPoolAccess() {
        try (var f = new HttpFixture()) {
            f.client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(request()).exchange()
                    .expectStatus().isEqualTo(503).expectHeader().doesNotExist("Retry-After").expectBody()
                    .jsonPath("$.status").isEqualTo(503).jsonPath("$.code").isEqualTo("CALENDAR_OWNER_UNAVAILABLE")
                    .jsonPath("$.message").isEqualTo("Paired durable calendar audit resources required");
        }
    }

    @Test void missingOwnerReadReturnsTyped503() {
        try (var f = new HttpFixture()) {
            f.client.get().uri(PATH).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.code").isEqualTo("CALENDAR_OWNER_UNAVAILABLE");
        }
    }

    @Test void missingOwnerReplacementReturnsTyped503() {
        try (var f = new HttpFixture()) {
            f.client.post().uri(PATH + "/replacements").bodyValue(request()).exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.code").isEqualTo("CALENDAR_OWNER_UNAVAILABLE");
        }
    }

    @Test void missingOwnerRestoreReturnsTyped503() {
        try (var f = new HttpFixture()) {
            var restore = new RestoreScheduleRequest(List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
            f.client.post().uri(PATH + "/restore").bodyValue(restore).exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.code").isEqualTo("CALENDAR_OWNER_UNAVAILABLE");
        }
    }

    @Test void validBeanButInvalidPeriodicBoundsReturnTyped400BeforeOwnerAccess() {
        try (var f = new HttpFixture()) {
            var request = request(); request.setTerm(601);
            f.client.post().uri(PATH).bodyValue(request).exchange().expectStatus().isBadRequest().expectBody()
                    .jsonPath("$.code").isEqualTo("CALENDAR_INPUT_INVALID");
        }
    }

    @Test void invalidReplacementCaptureReturnsTyped400BeforeOwnerAccess() {
        try (var f = new HttpFixture()) {
            var request = request(); request.setAnnualRatePercent(new BigDecimal("100.0001"));
            f.client.post().uri(PATH + "/replacements").bodyValue(request).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("CALENDAR_INPUT_INVALID");
        }
    }

    @Test void beanValidationRemainsTheActualShared400Contract() {
        try (var f = new HttpFixture()) {
            var request = request(); request.setPrincipal(null);
            f.client.post().uri(PATH).bodyValue(request).exchange().expectStatus().isBadRequest().expectBody()
                    .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test void typedOwnerFailureNegotiatesTheActualProblemJsonResponse() {
        try (var f = new HttpFixture()) {
            f.client.get().uri(PATH).accept(MediaType.APPLICATION_PROBLEM_JSON).exchange().expectStatus().isEqualTo(503)
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON).expectBody()
                    .jsonPath("$.status").isEqualTo(503).jsonPath("$.extensions.code").isEqualTo("CALENDAR_OWNER_UNAVAILABLE");
        }
    }
}
