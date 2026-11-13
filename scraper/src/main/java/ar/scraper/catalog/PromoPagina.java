package ar.scraper.catalog;

import ar.scraper.model.promo.PromoVigente;

import java.util.List;

/** One page of current promos plus the total that match the filter. */
public record PromoPagina(List<PromoVigente> promos, long total) {

    public PromoPagina {
        promos = promos == null ? List.of() : List.copyOf(promos);
    }
}
