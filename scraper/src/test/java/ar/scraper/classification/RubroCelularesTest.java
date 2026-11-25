package ar.scraper.classification;

import ar.scraper.indices.DeflactorPorRubro;
import ar.scraper.indices.Indice;
import ar.scraper.testsupport.TestRubros;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Clasificación")
@Feature("Rubro celulares")
@DisplayName("celulares: the Celular category on every kind of site")
class RubroCelularesTest {

    private static final String CELULARES = "celulares";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String CELULAR = "Celular";
    private static final List<String> SUBS = List.of("", "Smartphone", "Funda", "Protector", "Cargador", "Batería Externa", "Cable", "Soporte", "Repuesto");

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
            "cetrogar", sitio("cetrogar", null, "electrodomesticos"),
            "samsung", sitio("samsung", null, TECNOLOGIA))));

    @Test
    @DisplayName("a phone is celulares on a free site and on one whose default rubro is another, whatever its stored rubro or sub")
    void phonesLeaveTecnologia() {
        for (String sitio : List.of("libre", "cetrogar", "samsung")) {
            for (String sub : SUBS) {
                for (String guardado : List.of("", TECNOLOGIA, "hogar")) {
                    assertThat(resolver.resolver(sitio, CELULAR, guardado, sub)).as("'%s' on %s (stored '%s')", sub, sitio, guardado).isEqualTo(CELULARES);
                }
            }
        }
    }

    @Test
    @DisplayName("a store with a forced rubro keeps its phones: celulares weighs 0 as a category, like tecnologia did")
    void forcedStoresKeepTheirPhones() {
        for (String forzado : List.of(TECNOLOGIA, "oficina", "hogar", "construccion", "suplementos")) {
            for (String sub : SUBS) {
                assertThat(resolver.resolver(forzado, CELULAR, forzado, sub)).as("'%s' on %s", sub, forzado).isEqualTo(forzado);
            }
        }
    }

    @Test
    @DisplayName("the rest of technology stays tecnologia: Accesorio Apple, Tablet, Reloj, Auricular, Notebook")
    void restOfTechnologyStays() {
        for (String categoria : List.of("Accesorio Apple", "Tablet", "Reloj", "Auricular", "Notebook", "Cable")) {
            assertThat(resolver.resolver("libre", categoria, "")).as(categoria).isEqualTo(TECNOLOGIA);
        }
    }

    @Test
    @DisplayName("Celular has its own group: out of the tech set, in the canon")
    void categoryGroup() {
        assertThat(CategoryGroups.esCategoriaCelulares(CELULAR)).isTrue();
        assertThat(CategoryGroups.esCategoriaCelulares(null)).isFalse();
        assertThat(CategoryGroups.esCategoriaCelulares("Tablet")).isFalse();
        assertThat(CategoryGroups.esCategoriaTech(CELULAR)).isFalse();
        assertThat(CategoryGroups.canonicalCategories()).contains(CELULAR);
    }

    @Test
    @DisplayName("a phone is still priced in the dollar, like technology; a category of another rubro is not")
    void priceIndex() {
        DeflactorPorRubro deflactor = TestRubros.deflactor();

        assertThat(deflactor.resolver(CELULARES, CELULAR)).isEqualTo(Indice.USD_OFICIAL);
        assertThat(deflactor.resolver(CELULARES, "")).isEqualTo(Indice.USD_OFICIAL);
        assertThat(deflactor.resolver("hogar", "Colchón")).isEqualTo(Indice.IPC);
    }
}
