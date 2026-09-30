-- V41__status_notify.sql — backend-hardening, T3. Full rationale in docs/DATABASE.md;
-- this header is just the pointer.
--
-- The database announces status changes; nobody polls it. One channel,
-- `status_events`, fed by AFTER ROW triggers on the three tables whose status
-- the UI shows. pg_notify is transactional: listeners hear it only after COMMIT,
-- never for a rolled-back write. The payload carries identifiers and the new
-- status only — never error/log text (NOTIFY payloads are capped at 8000 bytes).
--
-- The function lives here and not in an R__ file: repeatable migrations run
-- after the versioned ones, so the triggers below would fail on a fresh database.
--
-- Rollback: docs/DATABASE.md, executed by V41RollbackRoundTripTest.

CREATE FUNCTION notify_status_change() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    payload jsonb;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NULL;
    END IF;

    IF TG_TABLE_NAME = 'scrape_run' THEN
        payload := jsonb_build_object('t', 'scrape_run', 'op', TG_OP,
                                      'id', NEW.id, 'status', NEW.status);
    ELSIF TG_TABLE_NAME = 'scrape_run_site' THEN
        payload := jsonb_build_object('t', 'scrape_run_site', 'op', TG_OP,
                                      'run', NEW.scrape_run_id, 'site', NEW.sitio_key,
                                      'status', NEW.status);
    ELSE
        payload := jsonb_build_object('t', 'cron_execution', 'op', TG_OP,
                                      'id', NEW.id, 'job', NEW.job_id, 'status', NEW.status);
    END IF;

    PERFORM pg_notify('status_events', payload::text);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_status_notify_scrape_run
    AFTER INSERT OR UPDATE ON scrape_run
    FOR EACH ROW EXECUTE FUNCTION notify_status_change();

CREATE TRIGGER trg_status_notify_scrape_run_site
    AFTER INSERT OR UPDATE ON scrape_run_site
    FOR EACH ROW EXECUTE FUNCTION notify_status_change();

CREATE TRIGGER trg_status_notify_cron_executions
    AFTER INSERT OR UPDATE ON cron_executions
    FOR EACH ROW EXECUTE FUNCTION notify_status_change();
