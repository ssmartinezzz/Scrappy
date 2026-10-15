-- Least-privilege login for Grafana. Idempotent: the init container runs it on
-- every `up`, and the password is set afterwards (see docker-compose.observability.yml).
--
-- SELECT on an allowlist, never the whole schema: `usuario`, `refresh_token`
-- and `password_reset_token` hold hashes and e-mails, and a dashboard has no
-- business reading them. A table added here later is a deliberate decision:
-- `notificacion_precio` is in because it holds ids, prices and states only (the
-- address is read from `usuario` at send time and masked in `ultimo_error`).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
        CREATE ROLE grafana_ro LOGIN;
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_ro', current_database());
END
$$;

ALTER ROLE grafana_ro SET default_transaction_read_only = on;
ALTER ROLE grafana_ro SET statement_timeout = '15s';

GRANT USAGE ON SCHEMA public TO grafana_ro;
GRANT SELECT ON
    productos,
    precio_historico,
    sitio,
    categoria,
    marca,
    scrape_run,
    scrape_run_site,
    cron_jobs,
    cron_executions,
    notificacion_precio
TO grafana_ro;
