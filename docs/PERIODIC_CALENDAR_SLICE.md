# Audited periodic calendar: first LEND-01 source slice

This change qualifies one existing Core repayment-calendar path. It does not establish origination-to-disbursement financial acceptance, live persistence, a complete lending journey or deployment readiness.

The four existing `/api/v1/loan-servicing-cases/{caseId}/repayment-schedules/calendar` operations now use the functional `AmortizationCalendarOperations` interface and its sole audited implementation. The old directly constructed `AmortizationCalendarService` is a non-bean compatibility bridge whose operations explicitly refuse without owner resources. Its constructor and method signatures remain; the controller now injects the interface. DTOs, URL shapes and checked-in SDK files are unchanged.

## Supported calculation contract

The command stages the entire replacement before any audit append or business write. It requires an actual persisted case, exact principal/rate/term/frequency agreement, and matching explicit amortization method. An omitted optional method uses the persisted case method rather than inventing a default.

| Field | First supported slice |
|---|---|
| Case status | PENDING or ACTIVE |
| Frequency and compounding | Both MONTHLY |
| Interest convention | REDUCING_BALANCE, THIRTY_360 |
| Dates | Origination on the first; first due date exactly one month later; maturity exactly term months after origination |
| Amortization | EQUAL_INSTALLMENT, EQUAL_PRINCIPAL or INTEREST_ONLY |
| Bounds | 1–600 periods; annual percentage 0–100 with at most four fractional digits; money at most 15 integer and two fractional digits |
| Fees | Zero; the existing request has no priced-fee contract |
| Optional installment | Must equal the calculated regular EQUAL_INSTALLMENT payment; it cannot silently create a balloon |

The existing calculator and HALF_UP cent rounding are reused. Staging checks nonnegative components, exact principal conservation, totals, monthly date sequence, decreasing balances and final zero. Unsupported interest/day-count/compounding/date conventions, BULLET/BALLOON_PAYMENT and paid or partial-payment histories refuse. There is no currency-specific policy, APR/fee pricing or automatic repricing. Before replacement or restoration, the owner must establish the intended terms on the existing case; the calendar request cannot change them. Existing unadopted rows with missing balances or inconsistent schedules refuse rather than receiving inferred values.

## Ownership and atomicity

There is deliberately no default `AuditedCalendarResources` or authorization bean. Without explicit owner installation, calendar operations refuse before querying persistence. The default application context does not borrow an unrelated business ConnectionFactory. Ordinary case entity/repository mapping and its default schema remain unchanged.

Owner wiring must supply the exact bounded factory/client/template/transaction-manager graph, actual `SpringDataTransactionalAuditAppender` authority, and a trusted `CalendarAuditAdmission`. A Permit constructor validates shape, not authentication. The adopter must use genuinely validated signed identity, preserve opaque issuer/subject/platform tenant, obtain actual policy PERMIT with fulfilled obligations, and establish the case-to-resource-tenant ownership binding. It must not derive resource UUIDs from principal text or accept caller body/header assertions as ownership. Empty/error/mismatched/currently unavailable authority refuses; a later permit cannot renew the original captured window. No authoritative memory registry or framework business persistence is introduced.

Within one original finite five-second operation budget, the implementation:

1. Checks trusted admission, then uses a genuine derived Spring Data `@Lock(PESSIMISTIC_WRITE)` query for the case.
2. Stages all supported terms and existing unpaid rows under the same owner root.
3. Appends actual canonical Kernel evidence, validates the returned canonical record, and refuses a replay or missing append.
4. Inserts or advances the optional mapped calendar revision using generated INSERT/exact version CAS and observed one-row counts.
5. Uses generated mapped schedule INSERT and fenced status/delete updates, requiring exactly one affected row each.
6. Reads the mapped resulting rows back before commit, then emits only after acknowledged commit, completed boundary and COMPLETE owned cleanup.

The effect digest binds the actual case terms, original authority binding and before/after row identities and amounts. It contains no borrower DTO dump. Invalid input, append refusal, stale/missing/multiple row counts and failed writes roll back the same root; a failed second replacement insert cannot leave previous rows superseded. Case locking plus the separate revision CAS provides the source concurrency protocol. Calls serialize on the existing case and use exact revision CAS; this is not an idempotency guarantee or a client-supplied expected-generation contract. Retained schedules are bounded at1200 rows per case; a larger history needs a separately reviewed lifecycle rather than automatic deletion or an enlarged limit. Physical PostgreSQL locking/concurrency/constraint behavior is a separate required gate, not a claim made by the controlled R2DBC test model.

A post-commit cleanup failure suppresses the result but cannot undo an already acknowledged commit. It is an unknown-effect/reconciliation outcome, not permission for blind retries. The existing restoration contract removes exactly the fenced current replacement row set and reactivates the specified earlier rows. This slice adds no automatic retry, broader history cleanup, counter reset or remote financial effect.

## Optional owner schema and remaining gates

[The optional revision resource](../core-lending-loan-servicing-models/src/main/resources/db/manual/audited-calendar-revision-v1.sql) is an explicit optional owner installation resource. It is outside the normal `classpath:db/migration` location, adds no column to ordinary cases, performs no backfill and grants no privilege. No installer is invoked by startup. The new row does not itself establish tenant ownership. Existing bindings cannot silently switch tenant or authority version; adoption/cutover requires explicit review.

Before runtime exposure, the owner still needs qualified framework dependency adoption, trusted signed identity and case-to-tenant authority wiring, canonical paired-audit schema/ACL/owner installation, fresh owned PostgreSQL transaction/CAS/concurrency/cancellation tests, and current full producer/Maven/SDK integration. Java scoped checks do not establish database-wide tenant RLS or immutable history. Other legacy single-row schedule/case/syndication/disbursement operations are not converted by this slice and must not be represented as audited financial acceptance.

The standard repository commands remain `mvn clean test` and `mvn verify`. They were not run for the source-only qualification. Its genuine-JAR direct compiler/JUnit replay is a separate result, not Maven, publication, live database or whole-journey acceptance.

## Bounded HTTP refusal contract

The actual calendar controller remains on `AmortizationCalendarOperations`; the
shared framework error handler renders its typed errors. Missing explicit
`AuditedCalendarResources` now returns503 with code
`CALENDAR_OWNER_UNAVAILABLE`, before business ConnectionFactory interaction. This
is missing installation, not an authorization decision or evidence of rollback.
It carries no automatic Retry-After recommendation.

Invalid inputs rejected while capturing the command, before the owned root,
return400 with fixed code `CALENDAR_INPUT_INVALID`. HTTP bean-validation errors
retain the shared `VALIDATION_ERROR`400 response. The shared handler also supports
negotiated Problem JSON. Messages do not contain caller terms, internal causes or
resource identifiers. URL/DTO/service interfaces and checked-in SDK/OpenAPI remain
unchanged; genuine producer/SDK regeneration and full Maven remain separate gates.

**Inside-root input/convention HTTP classification remains OPEN.** The bounded
root deliberately replaces work exceptions with technical transaction facts.
A captured private validation marker plus WORK/rollback/cleanup facts does not
prove that the original signal survived a deadline or rollback race. This block
does not unwrap or relabel those failures, move locked-case checks outside the
transaction, or convert arbitrary audit, database, authorization, timeout,
post-commit or cleanup failures to400/503. A separately qualified typed rejection
provenance protocol is required before claiming that contract. The retained
counterexamples are explicitly outside this passing first-block test claim.

The controller-context regression belongs in the web module. Core tests no longer
import project web/controller classes. Source qualification compiles production
modules in dependency order and Core tests without any project web classes;
WebFlux HTTP tests use the real controller, missing-owner service and shared
handler without an HTTP listener, database or invented admission. This proves the
bounded refusal paths, not startup/security/financial acceptance.
