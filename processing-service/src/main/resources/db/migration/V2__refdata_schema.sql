-- Reference data for enrichment and routing: correspondent banking
-- relationships, nostro accounts, rail characteristics (including cutoff
-- and amount bounds), and the business-day calendar used to roll a missed
-- cutoff or a non-business settlement date forward. Realistic enough to
-- route and roll against, not a real correspondent network or cutoff table.

CREATE SCHEMA refdata;

CREATE TABLE refdata.correspondent (
    creditor_agent_bic  TEXT PRIMARY KEY,
    correspondent_bic   TEXT NOT NULL,
    settlement_currency CHAR(3) NOT NULL
);

CREATE TABLE refdata.nostro_account (
    correspondent_bic TEXT NOT NULL,
    currency          CHAR(3) NOT NULL,
    nostro_account    TEXT NOT NULL,
    PRIMARY KEY (correspondent_bic, currency)
);

-- One row per rail: its settlement currency, the amount band it serves
-- (max_amount NULL = uncapped), whether it can settle same-day, and its
-- daily cutoff. RailRouter selects by currency + amount + urgency against
-- this table; ValidationChain's "amount within rail bounds" rule checks the
-- amount is plausible for *some* configured rail in that currency, before a
-- specific rail has been chosen.
CREATE TABLE refdata.rail_cutoff (
    rail        TEXT PRIMARY KEY,
    currency    CHAR(3) NOT NULL,
    min_amount  NUMERIC(18,5) NOT NULL,
    max_amount  NUMERIC(18,5),
    same_day    BOOLEAN NOT NULL,
    cutoff_time TIME NOT NULL,
    cutoff_zone TEXT NOT NULL
);

CREATE TABLE refdata.business_calendar (
    calendar_date DATE NOT NULL,
    currency      CHAR(3) NOT NULL,
    description   TEXT,
    PRIMARY KEY (calendar_date, currency)
);

-- Correspondent relationships and their nostro accounts. Any
-- creditor_agent_bic not listed here has no correspondent on file, which is
-- what exercises the RC01/STATIC_DATA enrichment failure on the
-- correspondent-resolution link specifically.
--   DEUTDEFFXXX (Deutsche Bank, EUR) and CHASUS33XXX (JPMorgan, USD) are the
--     complete, working pair used by the golden-path tests.
--   SCBLUS33XXX has a correspondent relationship but deliberately no nostro
--     account row below, to exercise RC01/STATIC_DATA on the nostro-lookup
--     link specifically, distinct from a missing correspondent.
--   NWBKGB2LXXX has both a correspondent relationship and a nostro account,
--     but refdata.rail_cutoff below has no GBP rail at all -- enrichment
--     succeeds and routing fails with AG01, isolating "no eligible rail"
--     from every enrichment-stage failure.
INSERT INTO refdata.correspondent (creditor_agent_bic, correspondent_bic, settlement_currency) VALUES
    ('DEUTDEFFXXX', 'DEUTDEFFXXX', 'EUR'),
    ('CHASUS33XXX', 'CHASUS33XXX', 'USD'),
    ('SCBLUS33XXX', 'SCBLUS33XXX', 'USD'),
    ('NWBKGB2LXXX', 'NWBKGB2LXXX', 'GBP');

INSERT INTO refdata.nostro_account (correspondent_bic, currency, nostro_account) VALUES
    ('DEUTDEFFXXX', 'EUR', 'NOSTRO-EUR-DEUTDEFF-001'),
    ('CHASUS33XXX', 'USD', 'NOSTRO-USD-CHASUS33-001'),
    ('NWBKGB2LXXX', 'GBP', 'NOSTRO-GBP-NWBKGB2L-001');

-- FEDWIRE: USD, high value (>= 100,000), same-day, 17:00 America/New_York.
-- ACH_EQUIV: USD, low-to-mid value (< 150,000), next-day (not same-day),
-- 15:00 America/New_York. The two bands deliberately overlap between
-- 100,000 and 150,000: below that overlap, only ACH_EQUIV is eligible and
-- urgency cannot change the outcome (there is no same-day option for a
-- genuinely small payment in this fixture set); within the overlap, an
-- urgent payment prefers FEDWIRE (same-day) while a routine one prefers the
-- cheaper ACH_EQUIV -- which is what makes urgency an actual routing input
-- rather than one that amount alone always overrides.
-- SEPA: EUR, no value split modelled, same-day, 15:00 Europe/Paris (CET).
INSERT INTO refdata.rail_cutoff (rail, currency, min_amount, max_amount, same_day, cutoff_time, cutoff_zone) VALUES
    ('FEDWIRE', 'USD', 100000.00, NULL, true, '17:00:00', 'America/New_York'),
    ('ACH_EQUIV', 'USD', 0.01, 149999.99, false, '15:00:00', 'America/New_York'),
    ('SEPA', 'EUR', 0.01, NULL, true, '15:00:00', 'Europe/Paris');

-- A small, deliberately fixed set of holidays -- enough to exercise
-- weekend-vs-holiday rolling and a month-boundary roll in tests, not a
-- real multi-year calendar.
INSERT INTO refdata.business_calendar (calendar_date, currency, description) VALUES
    ('2026-01-01', 'USD', 'New Year''s Day'),
    ('2026-01-01', 'EUR', 'New Year''s Day'),
    ('2026-08-31', 'USD', 'Labor Day (fixture)'),
    ('2026-12-25', 'USD', 'Christmas Day'),
    ('2026-12-25', 'EUR', 'Christmas Day'),
    ('2026-12-26', 'EUR', 'St. Stephen''s Day');
