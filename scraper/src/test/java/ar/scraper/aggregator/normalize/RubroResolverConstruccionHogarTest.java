package ar.scraper.aggregator.normalize;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Rubro")
@DisplayName("RubroResolver — rubros construccion y hogar")
class RubroResolverConstruccionHogarTest {

    private static RubroResolver resolver() {
        Map<String, SiteRegistry.Sitio> cache = new LinkedHashMap<>();
        cache.put("mrplacadeco", new SiteRegistry.Sitio(
                "Mrplacadeco", "mrplacadeco", "tiendanube", false, "construccion", "config"));
        cache.put("ambiancedeco", new SiteRegistry.Sitio(
                "Ambiancedeco", "ambiancedeco", "tiendanube", false, "hogar", "config"));
        cache.put("freres", new SiteRegistry.Sitio(
                "Freres", "freres", "shopify", false, null, "config"));
        cache.put("compragamer", new SiteRegistry.Sitio(
                "Compragamer", "compragamer", "compragamer", false, "tecnologia", "config"));
        cache.put("inpro", new SiteRegistry.Sitio(
                "Inpro", "inpro", "inpro", false, "oficina", "config"));
        return new RubroResolver(SiteRegistry.forTesting(cache));
    }

    @Test
    @DisplayName("ambiancedeco (forced hogar): a cladding is construccion, a textile is hogar")
    void mixedCatalogSplitsByCategory() {
        assertThat(resolver().resolver("ambiancedeco", "Placa Decorativa", null)).isEqualTo("construccion");
        assertThat(resolver().resolver("ambiancedeco", "Vinilo Decorativo", null)).isEqualTo("construccion");
        assertThat(resolver().resolver("ambiancedeco", "Césped Sintético", null)).isEqualTo("construccion");
        assertThat(resolver().resolver("ambiancedeco", "Funda", null)).isEqualTo("hogar");
        assertThat(resolver().resolver("ambiancedeco", "Almohadón", null)).isEqualTo("hogar");
    }

    @Test
    @DisplayName("an unclassified product keeps the forced rubro of its site")
    void unclassifiedKeepsTheForcedRubro() {
        assertThat(resolver().resolver("ambiancedeco", "Otros", null)).isEqualTo("hogar");
        assertThat(resolver().resolver("ambiancedeco", "Conjunto", null)).isEqualTo("hogar");
        assertThat(resolver().resolver("mrplacadeco", "Otros", null)).isEqualTo("construccion");
        assertThat(resolver().resolver("mrplacadeco", "", null)).isEqualTo("construccion");
    }

    @Test
    @DisplayName("a textile sold by a forced site is still indumentaria, a supplement still suplementos")
    void forcedSitesDoNotSwallowTheOtherRubros() {
        assertThat(resolver().resolver("mrplacadeco", "Remera", null)).isEqualTo("indumentaria");
        assertThat(resolver().resolver("ambiancedeco", "Zapatilla Running", null)).isEqualTo("indumentaria");
        assertThat(resolver().resolver("ambiancedeco", "Creatina", null)).isEqualTo("suplementos");
    }

    @Test
    @DisplayName("a home-textile category outside a forced site still resolves to hogar")
    void categoryAloneDecidesOnAnyOtherSite() {
        assertThat(resolver().resolver("freres", "Almohadón", null)).isEqualTo("hogar");
        assertThat(resolver().resolver("freres", "Manta", "indumentaria")).isEqualTo("hogar");
        assertThat(resolver().resolver("freres", "Adhesivo", null)).isEqualTo("construccion");
    }

    @Test
    @DisplayName("existing rubros are unchanged")
    void existingRubrosAreUnchanged() {
        assertThat(resolver().resolver("compragamer", "GPU", null)).isEqualTo("tecnologia");
        assertThat(resolver().resolver("inpro", "Silla", null)).isEqualTo("oficina");
        assertThat(resolver().resolver("freres", "Remera", null)).isEqualTo("indumentaria");
        assertThat(resolver().resolver("freres", "Otros", "tecnologia")).isEqualTo("tecnologia");
        assertThat(resolver().resolver("freres", "Creatina", null)).isEqualTo("suplementos");
    }
}
