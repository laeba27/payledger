-- Baseline migration: proves Flyway runs against a fresh database and records
-- version 1 in flyway_schema_history. Real tables (accounts, ledger, cards, payments)
-- arrive in the next slices as V2, V3, ...
SELECT 1;
