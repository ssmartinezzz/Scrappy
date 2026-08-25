package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the two catalogue seams on {@link TiendanubePage}
 * (add-morashop-and-fix-entreno-pagination, ADR-1).
 *
 * <p>{@code nombreSelectorJs()} already established the Template Method shape
 * here: the base returns the generic behaviour, a per-store subclass
 * specialises ONE thing without touching the shared extractor. These two
 * follow it.
 *
 * <p>{@code catalogoUrls()} exists because morashop has no single catalogue
 * URL at all — its {@code /productos/} is a themed landing with zero products
 * and the real ones live under twelve leaf categories.
 *
 * <p>{@code usaApi()} is a CORRECTNESS seam, not a performance one, and that
 * distinction is the whole reason it exists. Morashop's storefront API 404s
 * today, so skipping it merely saves two navigations. But morashop also sells
 * supermercado, electro-hogar and bodega, and the Tiendanube products API
 * returns the WHOLE store. If that endpoint is ever enabled server-side, a
 * page that still tried it would quietly import three rubros that have no
 * value in the {@code rubro} domain. Returning false designs that out instead
 * of depending on the endpoint staying broken.
 */
@Epic("Scraping Engine")
@Feature("TiendaNube Parsing")
@Story("Catalogue seams")
@DisplayName("TiendanubePage — catalogue seams")
class TiendanubePageSeamsTest {

    private TiendanubePage base() {
        return new TiendanubePage(null, 0, "x", "https://x.com/productos/", 0, 1);
    }

    @Test
    @DisplayName("por defecto el catalogo es la baseUrl y nada mas")
    void baseCatalogueIsJustTheBaseUrl() {
        assertThat(base().catalogoUrls()).containsExactly("https://x.com/productos/");
    }

    @Test
    @DisplayName("por defecto se intenta la API — el camino rapido sigue siendo el default")
    void baseTriesTheApi() {
        assertThat(base().usaApi()).isTrue();
    }

    @Test
    @DisplayName("Monkyforce no cambia ninguno de los dos: solo especializa el nombre")
    void monkyforceLeavesBothSeamsAlone() {
        MonkyforcePage mf = new MonkyforcePage(null, 0, "Monkyforce",
                "https://www.monkyforce.com/productos/", 0, 1, List.of());
        assertThat(mf.catalogoUrls()).containsExactly("https://www.monkyforce.com/productos/");
        assertThat(mf.usaApi()).isTrue();
    }
}
