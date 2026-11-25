package ar.scraper.web.cache;

import ar.scraper.catalog.PoliticaStock;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keyset pages over the cached rankings. It is a separate bean on purpose: a page method inside
 * {@link CatalogoDerivadoCache} would call the {@code @Cacheable} methods through {@code this}, skip the proxy and
 * re-rank the whole catalog on every request. Here every ranking is read through the injected proxy.
 */
@Component
public class CatalogoPaginas {

    private final CatalogoDerivadoCache derivados;
    private final PoliticaStock politicaStock;

    /** Default stock policy (7 days, system clock): for callers that build the bean by hand. */
    public CatalogoPaginas(CatalogoDerivadoCache derivados) {
        this(derivados, PoliticaStock.porDefecto());
    }

    @Autowired
    public CatalogoPaginas(CatalogoDerivadoCache derivados, PoliticaStock politicaStock) {
        this.derivados = derivados;
        this.politicaStock = politicaStock;
    }

    public MarcasPicksDtos.MarcasPagina marcasPagina(MarcasKey k, String cursor, int limite) {
        return MarcasPicksView.paginaMarcas(derivados.marcas(k), k.sort(), cursor, limite);
    }

    public MarcasPicksDtos.PicksPagina picks(MejoresKey k, String categoria, String cursor, int limite) {
        return picks(k, categoria, null, cursor, limite);
    }

    /**
     * Like {@link #picks(MejoresKey, String, String, int)} restricted to one stored {@code subCategoria} (exact match
     * after trimming; blank means no restriction). The cached ranking is filtered BEFORE paging, so cursor, nextCursor
     * and total all describe the filtered list. The filter keeps the ranking order, so the keyset binary search still
     * applies; it is one O(n) pass over a single category per request, not memoized.
     */
    public MarcasPicksDtos.PicksPagina picks(MejoresKey k, String categoria, String subCategoria, String cursor, int limite) {
        var ranking = derivados.rankingPicks(k).getOrDefault(categoria, List.of());
        return MarcasPicksView.pagina(MarcasPicksView.filtrarPorSub(ranking, subCategoria), cursor, limite, politicaStock);
    }
}
