-- Optional owner installation, never a startup/Flyway migration or a grant script.
-- Install only with the canonical paired-audit owner profile and scoped authority adapter.
-- Existing calendar rows are retained. First adoption validates their exact current terms;
-- this relation neither assigns tenant ownership nor backfills an inferred owner.
CREATE TABLE public.loan_calendar_revision (
    case_id UUID PRIMARY KEY REFERENCES public.loan_servicing_case (loan_servicing_case_id),
    resource_tenant_id UUID NOT NULL,
    binding_id UUID NOT NULL,
    binding_version BIGINT NOT NULL CHECK (binding_version > 0),
    binding_digest VARCHAR(64) NOT NULL CHECK (binding_digest ~ '^[0-9a-f]{64}$'),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    intent_digest VARCHAR(64) NOT NULL CHECK (intent_digest ~ '^[0-9a-f]{64}$')
);
