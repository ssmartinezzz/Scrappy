package ar.scraper.scrape;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The most recent finished run and the sites that ended in {@code ERROR}. */
public record UltimaCorrida(UUID uuid, Instant finishedAt, List<SitioFallido> fallidos) {

    /** Error text the run writes for a site still unfinished when the global budget ran out. */
    public static final String ERROR_DEADLINE_GLOBAL = "Deadline global";

    /** {@code sitio} is the registry name, the spelling {@code POST /api/scrape?sitios=} takes. */
    public record SitioFallido(String sitio, String error, boolean porDeadline) { }
}
