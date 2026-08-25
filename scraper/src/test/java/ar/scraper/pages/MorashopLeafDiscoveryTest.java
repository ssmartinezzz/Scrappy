package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for morashop's leaf-category discovery
 * (add-morashop-and-fix-entreno-pagination, ADR-2).
 *
 * <p>Morashop has no catalogue URL. Its {@code /productos/} is a themed
 * "8 CATEGORÍAS" landing with zero products, and {@code /suplementos/} is only
 * an index of subcategories — also zero products. Every product lives one
 * level down, in a leaf category. Pointing the scraper at either index yields
 * nothing, silently: the V24 bug class.
 *
 * <p>Discovery is split the way {@code resolveNextPageFromHrefs} is: a pure
 * static over href strings, tested here with fixtures, plus a three-line
 * browser edge that harvests {@code a[href]}. The untested surface is the
 * harvest, not the rules.
 *
 * <p>The fixture below is the real shape of that landing, noise included —
 * absolute and root-relative forms, tracking query strings, the section index
 * linking to itself, sibling sections, and a duplicate.
 */
@Epic("Scraping Engine")
@Feature("Morashop")
@Story("Leaf category discovery")
@DisplayName("MorashopPage — leaf category discovery from landing hrefs")
class MorashopLeafDiscoveryTest {

    private static final String SECCION = "https://www.morashop.ar/suplementos/";

    private static final List<String> LEAVES = List.of(
            "proteinas", "creatinas", "bcaa-aminos", "vitaminas-y-salud",
            "quemadores", "pre-entrenos", "barras-snacks", "ganadores",
            "cafeina-energia", "carbo-isotonicos", "suplementos-importados", "shakers");

    private static List<String> landingHrefs() {
        List<String> hrefs = new ArrayList<>(List.of(
                "/account/login/",
                "/account/register",
                "https://www.morashop.ar",
                "https://morashopmayorista.ar",
                "https://www.morashop.ar/suplementos/",
                "/suplementos/",
                "https://www.morashop.ar/supermercado/",
                "https://www.morashop.ar/supermercado/aceites-vinagres/",
                "https://www.morashop.ar/electro-hogar/heladeras-lavarropas/",
                "https://www.morashop.ar/bodega/vinos-tintos/",
                "https://www.morashop.ar/marcas/",
                "/productos/",
                "#",
                "javascript:void(0)",
                ""));
        for (String leaf : LEAVES) {
            hrefs.add("https://www.morashop.ar/suplementos/" + leaf + "/");
        }
        hrefs.add("/suplementos/proteinas/");                       // duplicate, relative form
        hrefs.add("/suplementos/creatinas/?utm_source=nav");        // duplicate, with query
        return hrefs;
    }

    @Test
    @DisplayName("encuentra las 12 hojas y ninguna otra cosa")
    void findsExactlyTheTwelveLeaves() {
        List<String> hojas = MorashopPage.hojasDeCategoria(landingHrefs(), SECCION);

        assertThat(hojas).hasSize(12);
        assertThat(hojas).containsExactlyElementsOf(
                LEAVES.stream().map(l -> SECCION + l + "/").toList());
    }

    @Test
    @DisplayName("excluye las secciones hermanas — es la garantia de alcance del cambio entero")
    void excludesSiblingSections() {
        List<String> hojas = MorashopPage.hojasDeCategoria(landingHrefs(), SECCION);

        // morashop also sells supermercado, electro-hogar and bodega. None of
        // them maps to a value in the four-value `rubro` domain, so crawling
        // them would force a lie about what the product is. This assertion is
        // what keeps the change scoped to supplements.
        assertThat(hojas).noneMatch(h -> h.contains("/supermercado/"));
        assertThat(hojas).noneMatch(h -> h.contains("/electro-hogar/"));
        assertThat(hojas).noneMatch(h -> h.contains("/bodega/"));
    }

    @Test
    @DisplayName("excluye el indice de la seccion, que no tiene productos")
    void excludesTheSectionIndexItself() {
        assertThat(MorashopPage.hojasDeCategoria(landingHrefs(), SECCION))
                .doesNotContain(SECCION);
    }

    @Test
    @DisplayName("deduplica las formas absoluta, relativa y con query de la misma hoja")
    void dedupesEquivalentForms() {
        List<String> hojas = MorashopPage.hojasDeCategoria(landingHrefs(), SECCION);

        assertThat(hojas.stream().filter(h -> h.endsWith("/proteinas/")).count()).isEqualTo(1);
        assertThat(hojas.stream().filter(h -> h.endsWith("/creatinas/")).count()).isEqualTo(1);
        assertThat(hojas).noneMatch(h -> h.contains("?"));
    }

    @Test
    @DisplayName("ignora un href de otro host aunque cuelgue del mismo path")
    void ignoresForeignHosts() {
        List<String> hojas = MorashopPage.hojasDeCategoria(
                List.of("https://morashopmayorista.ar/suplementos/proteinas/"), SECCION);

        assertThat(hojas).isEmpty();
    }

    @Test
    @DisplayName("una sub-sub-categoria no es una hoja de esta seccion")
    void ignoresDeeperPaths() {
        List<String> hojas = MorashopPage.hojasDeCategoria(
                List.of("https://www.morashop.ar/suplementos/proteinas/veganas/"), SECCION);

        assertThat(hojas).isEmpty();
    }

    @Test
    @DisplayName("sin hojas el helper devuelve vacio — quien decide que eso es un error es la page")
    void returnsEmptyWhenNothingMatches() {
        assertThat(MorashopPage.hojasDeCategoria(List.of("/account/login/", "#"), SECCION))
                .isEmpty();
        assertThat(MorashopPage.hojasDeCategoria(List.of(), SECCION)).isEmpty();
    }

    // ─── el throw ──────────────────────────────────────────────────────────
    // La propiedad de seguridad central de esta clase. Sin ella, un landing
    // cuyo markup cambió se ve exactamente igual que una tienda vacía, y
    // SiteYieldGuard no puede distinguirlos porque sólo alerta CAÍDAS contra
    // la corrida anterior — un sitio que rinde cero desde la primera corrida
    // nunca lo despierta. Se testea con Page = null: `hojasOFalla` no toca el
    // browser, justamente para que esto sea verificable.

    private MorashopPage page() {
        return new MorashopPage(null, 0, "Morashop", SECCION, 0, Double.MAX_VALUE, List.of(), 60);
    }

    @Test
    @DisplayName("descubrimiento vacio TIRA, nunca devuelve lista vacia")
    void emptyDiscoveryThrows() {
        assertThatThrownBy(() -> page().hojasOFalla(List.of()))
                .isInstanceOf(MorashopDiscoveryException.class)
                .hasMessageContaining(SECCION);
    }

    @Test
    @DisplayName("un landing lleno de links que no son hojas tambien TIRA")
    void landingWithNoLeavesThrows() {
        // El caso realista: el tema cambió y las categorías ya no cuelgan de
        // /suplementos/. Hay links de sobra, ninguno es una hoja.
        assertThatThrownBy(() -> page().hojasOFalla(List.of(
                "/account/login/",
                "https://www.morashop.ar/supermercado/aceites-vinagres/",
                "https://www.morashop.ar/bodega/vinos-tintos/",
                "/productos/",
                "#")))
                .isInstanceOf(MorashopDiscoveryException.class);
    }

    @Test
    @DisplayName("con hojas NO tira y devuelve las 12")
    void discoveryWithLeavesDoesNotThrow() {
        assertThat(page().hojasOFalla(landingHrefs())).hasSize(12);
    }
}
