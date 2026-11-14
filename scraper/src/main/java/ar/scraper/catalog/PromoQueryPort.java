package ar.scraper.catalog;

import ar.scraper.model.promo.PromoVigente;

import java.util.List;
import java.util.Map;

/**
 * Read side of the payment promos. Writes happen inside the run's product upsert
 * ({@code sp_upsert_promos}), so they are not here. "Current" means {@code activo} and inside its
 * vigencia window today.
 */
public interface PromoQueryPort {

    /** No promos at all: for callers that build a controller by hand. */
    PromoQueryPort VACIO = new PromoQueryPort() {
        @Override
        public PromoPagina buscar(String sitio, String medio, int page, int size) {
            return new PromoPagina(List.of(), 0);
        }

        @Override
        public Map<String, List<PromoVigente>> paraProductos(List<ProductoRef> productos) {
            return Map.of();
        }
    };

    /**
     * Current promos, optionally of one site ({@code sitio} = display name) and/or one medio ({@code medio} =
     * canonical name, matched against the medio OR the required card network). Blank means no filter. Zero-based
     * {@code page}.
     */
    PromoPagina buscar(String sitio, String medio, int page, int size);

    /**
     * The current promos that apply to each product, keyed by url: its own product-scoped promos plus its site's
     * site-scoped ones. Set-based: a fixed number of queries whatever the page size. A product with none is absent.
     */
    Map<String, List<PromoVigente>> paraProductos(List<ProductoRef> productos);
}
