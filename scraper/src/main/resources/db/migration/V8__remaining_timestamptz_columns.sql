-- V8__remaining_timestamptz_columns.sql — normalize-db-schema-fks-1nf, slice A.4
-- (spec "db-column-domains", design D6 as overridden: every date/time column
-- gets a real type in this change, not only the two DATE ones)
--
-- V5 retyped exactly the timestamps sp_upsert_run / sp_soft_delete_ausentes
-- write (productos.touched_at/created_at) because Postgres has no partial
-- function redefinition and carrying a 90-line body forward is the expensive
-- part of that kind of change. The 19 columns below are written from plain
-- Java statements, so they cost NO function re-copy — which is exactly why
-- they ride in their own slice instead of inflating V5.
--
-- Every value stored so far was produced by
-- `LocalDateTime.now().format("yyyy-MM-dd HH:mm:ss")` (Java) or
-- `datetime.now(timezone.utc).isoformat()` (Python, image_embeddings). The
-- USING cast interprets the offset-less ones in the session timezone, which is
-- the same timezone that wrote them — the instant is preserved, and the ones
-- that already carry an offset keep it.
--
-- NULLable columns stay NULLable: last_run_at/next_run_at/finished_at/
-- last_checked_at/added_at/bloqueado_at genuinely mean "has not happened yet".
--
-- productos.bloqueado_at is retyped WITH its V3 CHECK
-- (bloqueado_por IS NULL) = (bloqueado_at IS NULL) in place; Postgres
-- re-validates the constraint against the new type as part of the ALTER.

ALTER TABLE productos              ALTER COLUMN bloqueado_at    TYPE timestamptz USING bloqueado_at::timestamptz;
ALTER TABLE image_embeddings       ALTER COLUMN computed_at     TYPE timestamptz USING computed_at::timestamptz;
ALTER TABLE ml_output              ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE sitios_dinamicos       ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE categoria_stats        ALTER COLUMN updated_at      TYPE timestamptz USING updated_at::timestamptz;
ALTER TABLE favoritos              ALTER COLUMN added_at        TYPE timestamptz USING added_at::timestamptz;
ALTER TABLE favoritos              ALTER COLUMN last_checked_at TYPE timestamptz USING last_checked_at::timestamptz;
ALTER TABLE outfit_feedback        ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE outfit_feedback_item   ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE categoria_dismiss      ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE financiacion_presets   ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE saved_outfits          ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE cron_jobs              ALTER COLUMN created_at      TYPE timestamptz USING created_at::timestamptz;
ALTER TABLE cron_jobs              ALTER COLUMN updated_at      TYPE timestamptz USING updated_at::timestamptz;
ALTER TABLE cron_jobs              ALTER COLUMN last_run_at     TYPE timestamptz USING last_run_at::timestamptz;
ALTER TABLE cron_jobs              ALTER COLUMN next_run_at     TYPE timestamptz USING next_run_at::timestamptz;
ALTER TABLE cron_executions        ALTER COLUMN started_at      TYPE timestamptz USING started_at::timestamptz;
ALTER TABLE cron_executions        ALTER COLUMN finished_at     TYPE timestamptz USING finished_at::timestamptz;
ALTER TABLE agent_reclassify_audit ALTER COLUMN applied_at      TYPE timestamptz USING applied_at::timestamptz;
