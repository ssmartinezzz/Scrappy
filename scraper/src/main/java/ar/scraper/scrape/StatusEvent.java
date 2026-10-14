package ar.scraper.scrape;

import java.util.List;

public sealed interface StatusEvent {

    record ScrapeStatus(ScraperStatus status, String message) implements StatusEvent {}

    record ScrapeProgress(int total, int completados, int productos, List<SiteProgress> sitios)
            implements StatusEvent {}

    record SiteProgress(String nombre, String estado, int productos, String error, long duracionMs) {}

    record MlStatus(Kind kind, boolean running, String phase, int pct, String message, String startedAt)
            implements StatusEvent {
        public enum Kind { TRAINING, BACKFILL }
    }

    /** Fields that do not apply to a table are null. */
    record DbChanged(String table, String op, Long id, Long run, String site, Long job, String status)
            implements StatusEvent {}

    /** A scrape met a Cloudflare challenge; {@code detalle} is a short human-readable note. */
    record ScrapeCloudflare(String sitio, Estado estado, String detalle) implements StatusEvent {
        public enum Estado { DETECTADA, RESUELTA, BLOQUEADA }
    }

    record Resync() implements StatusEvent {}
}
