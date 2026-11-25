package ar.scraper.classification;

import ar.scraper.indices.DeflactorPorRubro;
import ar.scraper.indices.Indice;
import ar.scraper.testsupport.TestRubros;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Clasificación")
@Feature("Rubro electrodomesticos")
@DisplayName("electrodomesticos: the 11 appliance categories and the 8 TV y Audio subs, on every kind of site")
class RubroElectrodomesticosTest {

    private static final String ELECTRO = "electrodomesticos";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String TV_Y_AUDIO = "TV y Audio";
    private static final List<String> CATEGORIAS = List.of(
            "Aire Acondicionado", "Aspiradora", "Calefactor", "Calefón", "Campana y Extractor", "Cocina y Horno", "Heladera",
            "Lavarropas", "Pequeño Electrodoméstico", "Termotanque", "Ventilador");
    private static final List<String> SUBS_ELECTRO = List.of(
            "Televisor", "Parlante", "Soporte", "Barra de Sonido", "Proyector", "Radio", "Tocadiscos", "Equipo de Música");
    private static final List<String> SUBS_TECNOLOGIA = List.of("", "Handy", "Consola y Mixer");

    private static SiteRegistry.Sitio sitio(String clave, String forzado, String porDefecto) {
        return new SiteRegistry.Sitio(clave, clave, "vtex", false, forzado, "config", false, porDefecto);
    }

    private final RubroResolver resolver = TestRubros.resolver(SiteRegistry.forTesting(Map.of(
            "libre", sitio("libre", null, null),
            "tecnologia", sitio("tecnologia", TECNOLOGIA, null),
            "oficina", sitio("oficina", "oficina", null),
            "hogar", sitio("hogar", "hogar", null),
            "construccion", sitio("construccion", "construccion", null),
            "suplementos", sitio("suplementos", "suplementos", null),
            "cetrogar", sitio("cetrogar", null, ELECTRO),
            "samsung", sitio("samsung", null, TECNOLOGIA))));

    @Test
    @DisplayName("the 11 categories are electrodomesticos on a free site and on a hogar, construccion or suplementos one")
    void appliancesLeaveHogar() {
        for (String sitio : List.of("libre", "hogar", "construccion", "suplementos", "cetrogar", "samsung")) {
            for (String categoria : CATEGORIAS) {
                assertThat(resolver.resolver(sitio, categoria, "hogar")).as("%s on %s", categoria, sitio).isEqualTo(ELECTRO);
            }
        }
    }

    @Test
    @DisplayName("a forced tecnologia or oficina store keeps them: those rubros weigh at least as much")
    void forcedTecnologiaAndOficinaKeepThem() {
        for (String categoria : CATEGORIAS) {
            assertThat(resolver.resolver("tecnologia", categoria, "tecnologia")).as(categoria).isEqualTo(TECNOLOGIA);
            assertThat(resolver.resolver("oficina", categoria, "oficina")).as(categoria).isEqualTo("oficina");
        }
    }

    @Test
    @DisplayName("the 8 appliance subs of TV y Audio are electrodomesticos on a free site, whatever the site's default or stored rubro")
    void tvAndAudioSubsMove() {
        for (String sitio : List.of("libre", "cetrogar", "samsung")) {
            for (String sub : SUBS_ELECTRO) {
                assertThat(resolver.resolver(sitio, TV_Y_AUDIO, TECNOLOGIA, sub)).as("%s on %s", sub, sitio).isEqualTo(ELECTRO);
            }
        }
        assertThat(resolver.resolver("libre", TV_Y_AUDIO, "", "Televisor")).isEqualTo(ELECTRO);
    }

    @Test
    @DisplayName("Handy, Consola y Mixer and a blank sub stay tecnologia, on a free site and on a forced one")
    void restOfTvAndAudioStays() {
        for (String sitio : List.of("libre", "tecnologia", "samsung")) {
            for (String sub : SUBS_TECNOLOGIA) {
                assertThat(resolver.resolver(sitio, TV_Y_AUDIO, TECNOLOGIA, sub)).as("'%s' on %s", sub, sitio).isEqualTo(TECNOLOGIA);
            }
        }
        assertThat(resolver.resolver("libre", TV_Y_AUDIO, TECNOLOGIA, null)).isEqualTo(TECNOLOGIA);
    }

    @Test
    @DisplayName("a forced store keeps every TV y Audio sub: the sub row decides only on a site with no forced rubro")
    void forcedStoresKeepTheirTvAndAudio() {
        List<String> casos = new ArrayList<>();
        for (String sitio : List.of("tecnologia", "oficina", "hogar", "construccion", "suplementos")) {
            for (String sub : SUBS_ELECTRO) {
                String esperado = resolver.resolver(sitio, TV_Y_AUDIO, "x", "");
                if (!esperado.equals(resolver.resolver(sitio, TV_Y_AUDIO, "x", sub))) casos.add(sitio + "/" + sub);
            }
        }
        assertThat(casos).isEmpty();
        assertThat(resolver.resolver("tecnologia", TV_Y_AUDIO, TECNOLOGIA, "Televisor")).isEqualTo(TECNOLOGIA);
    }

    @Test
    @DisplayName("Cetrogar: what no category decides reads as an appliance; a category with a row keeps its rubro")
    void cetrogarDefault() {
        for (String categoria : List.of("Otros", "Llavero", "Conjunto")) {
            assertThat(resolver.resolver("cetrogar", categoria, TECNOLOGIA)).as(categoria).isEqualTo(ELECTRO);
        }
        assertThat(resolver.resolver("cetrogar", "Notebook", TECNOLOGIA)).isEqualTo(TECNOLOGIA);
        assertThat(resolver.resolver("cetrogar", "Colchón", TECNOLOGIA)).isEqualTo("hogar");
        assertThat(resolver.resolver("samsung", "Otros", TECNOLOGIA)).as("Samsung's default stays tecnologia").isEqualTo(TECNOLOGIA);
    }

    @Test
    @DisplayName("TV y Audio keeps the dollar although its rubro is IPC; an appliance is deflated by IPC, as it was under hogar")
    void priceIndex() {
        DeflactorPorRubro deflactor = TestRubros.deflactor();

        assertThat(deflactor.resolver(ELECTRO, TV_Y_AUDIO)).isEqualTo(Indice.USD_OFICIAL);
        assertThat(deflactor.resolver(ELECTRO, "Heladera")).isEqualTo(Indice.IPC);
        assertThat(deflactor.resolver(ELECTRO, "Pequeño Electrodoméstico")).isEqualTo(Indice.IPC);
    }
}
