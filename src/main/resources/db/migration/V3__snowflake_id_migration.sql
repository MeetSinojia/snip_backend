-- V3__snowflake_id_migration.sql
-- Replace PostgreSQL auto-increment (BIGSERIAL) with application-managed Snowflake IDs.
--
-- Why: BIGSERIAL requires an INSERT to discover the ID, forcing the old two-step
-- save in UrlServiceImpl (save "pending" → update shortCode). Snowflake IDs are
-- generated in the application layer before any DB call, enabling:
--   1. Single-step INSERT (id + shortCode computed together)
--   2. Shard routing before transaction opens (ID → shortCode → shard)
--   3. Globally unique IDs across all shards without DB coordination
--
-- Migration steps:
--   1. Drop the default (nextval from sequence) on the id column
--   2. Drop the now-unused sequence
--   (Column type stays BIGINT — BIGSERIAL is just BIGINT + sequence + default)

ALTER TABLE urls ALTER COLUMN id DROP DEFAULT;

-- Drop the implicit sequence created by BIGSERIAL
-- (sequence name follows PostgreSQL convention: {table}_{column}_seq)
DROP SEQUENCE IF EXISTS urls_id_seq;

-- click_events.id also has BIGSERIAL — apply same change
ALTER TABLE click_events ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS click_events_id_seq;

-- Verify: at this point both id columns are plain BIGINT.
-- Application is now responsible for providing a valid Snowflake ID on INSERT.