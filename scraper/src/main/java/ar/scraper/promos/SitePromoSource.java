package ar.scraper.promos;

import ar.scraper.model.promo.PromosDelSitio;

import java.time.LocalDate;

/**
 * Reads the promos a store publishes for the whole site, once per site per run. Contract: a successful
 * read returns {@link PromosDelSitio#leidas} (an empty list is a real answer and lets the sweep
 * deactivate old promos); ANY failure returns {@link PromosDelSitio#NO_LEIDO} so a bad day never wipes
 * what was stored. Implementations never throw.
 */
public interface SitePromoSource {

    /** The site does not read promos (kill switch off, or no source wired): its stored promos are kept. */
    SitePromoSource NINGUNA = (baseUrl, web, hoy) -> PromosDelSitio.NO_LEIDO;

    PromosDelSitio leer(String baseUrl, PaginaWeb web, LocalDate hoy);
}
