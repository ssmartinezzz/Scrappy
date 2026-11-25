package ar.scraper.classification;

import ar.scraper.testsupport.TestRubros;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Clasificación")
@Feature("Rubro por tablas")
@DisplayName("RubroResolver sobre tablas == la cadena de if de antes más las mudanzas de V139 y V140, combinación por combinación")
class RubroResolverEquivalenceTest {

    private static final String ELECTRO = "electrodomesticos";
    private static final String CELULARES = "celulares";
    private static final String TV_Y_AUDIO = "TV y Audio";
    private static final List<String> SUBS = Arrays.asList(
            null, "", "comedor", "exterior", "infantil", "gamer", "Televisor", "Equipo de Música", "Handy", "Consola y Mixer");
    private static final List<String> FORZADOS =
            Arrays.asList(null, "", "tecnologia", "suplementos", "oficina", "construccion", "hogar");
    private static final List<String> POR_DEFECTO =
            Arrays.asList(null, "", "tecnologia", "suplementos", "oficina", "construccion", "hogar", "juguetes", ELECTRO);
    private static final List<String> GUARDADOS = new ArrayList<>(Arrays.asList(null, ""));

    static {
        TestRubros.catalogo().rubros().forEach(r -> GUARDADOS.add(r.clave()));
    }

    @Test
    @DisplayName("0 differences over every category x sub x forced x default x stored, apart from the V139 and V140 deltas")
    void sameAnswerEverywhere() {
        List<String> categorias = new ArrayList<>(new TreeSet<>(CategoryGroups.canonicalCategories()));
        categorias.addAll(Arrays.asList(null, "", "Categoria Inexistente"));

        Map<String, SiteRegistry.Sitio> sitios = new HashMap<>();
        for (String forzado : FORZADOS) {
            for (String porDefecto : POR_DEFECTO) {
                sitios.put(clave(forzado, porDefecto), new SiteRegistry.Sitio(
                        clave(forzado, porDefecto), clave(forzado, porDefecto), "tiendanube", false, forzado,
                        "config", false, porDefecto));
            }
        }
        SiteRegistry registry = SiteRegistry.forTesting(sitios);
        RubroResolver nuevo = TestRubros.resolver(registry);
        RubroResolverLegacy viejo = new RubroResolverLegacy(registry);

        long combinaciones = 0;
        long lanzoElViejo = 0;
        List<String> diferencias = new ArrayList<>();
        for (String categoria : categorias) {
            for (String sub : SUBS) {
                for (String forzado : FORZADOS) {
                    for (String porDefecto : POR_DEFECTO) {
                        String sitio = clave(forzado, porDefecto);
                        for (String guardado : GUARDADOS) {
                            combinaciones++;
                            String esperado;
                            try {
                                esperado = conLasMudanzas(viejo.resolver(sitio, categoria, guardado, sub), categoria, sub, forzado);
                            } catch (NullPointerException e) {
                                lanzoElViejo++;
                                assertThat(categoria).as("the old chain only threw on a null category").isNull();
                                assertThat(nuevo.resolver(sitio, categoria, guardado, sub)).isNotBlank();
                                continue;
                            }
                            String actual = nuevo.resolver(sitio, categoria, guardado, sub);
                            if (!esperado.equals(actual) && diferencias.size() < 20) {
                                diferencias.add("cat=%s sub=%s forced=%s default=%s stored=%s: old=%s new=%s"
                                        .formatted(categoria, sub, forzado, porDefecto, guardado, esperado, actual));
                            }
                        }
                    }
                }
            }
        }

        System.out.println("[RubroResolverEquivalenceTest] combinations compared: " + combinaciones
                + " (the old chain threw a NullPointerException on " + lanzoElViejo + ", all with a null category)");
        assertThat(diferencias).isEmpty();
        assertThat(combinaciones).isGreaterThan(1_000_000L);
    }

    @Test
    @DisplayName("the 3-argument overload is the 4-argument one with an empty sub")
    void threeArgumentOverload() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of());
        RubroResolver nuevo = TestRubros.resolver(registry);

        assertThat(nuevo.resolver("x", "Silla", "")).isEqualTo(nuevo.resolver("x", "Silla", "", ""));
    }

    /**
     * The only intended difference from the old chain (V139): an appliance category the old chain called hogar is
     * electrodomesticos, and so is a TV y Audio sub that is an appliance on a site with no forced rubro. A forced
     * tecnologia or oficina site still keeps both, because those rubros weigh at least as much as electrodomesticos.
     * And V140: a phone the old chain called tecnologia is celulares on a site with no forced rubro (a forced site keeps its own).
     * RubroResolverLegacy still reads Celular as tech, so the old answer is the one before both moves.
     */
    private static String conLasMudanzas(String viejo, String categoria, String sub, String forzado) {
        if ("hogar".equals(viejo) && CategoryGroups.esCategoriaElectrodomesticos(categoria)) return ELECTRO;
        boolean sitioLibre = forzado == null || forzado.isEmpty();
        if (sitioLibre && "tecnologia".equals(viejo) && CategoryGroups.esCategoriaCelulares(categoria)) return CELULARES;
        if (sitioLibre && TV_Y_AUDIO.equals(categoria) && sub != null && TestRubros.SUBS_ELECTRO_DE_TV_Y_AUDIO.contains(sub)) return ELECTRO;
        return viejo;
    }

    private static String clave(String forzado, String porDefecto) {
        return "f=" + forzado + "|d=" + porDefecto;
    }
}
