package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * add-inpro-office-store: {@code oficina} como cuarto rubro.
 *
 * <p>La rama nueva de {@link RubroResolver} es un espejo exacto de la de
 * {@code tecnologia} —incluido el guard {@code !catEsTextil}— y eso es
 * deliberado, no copiar y pegar: una tienda de rubro único que además vende una
 * remera con su logo tiene que dar {@code indumentaria} para esa remera. El
 * sitio fuerza el rubro de lo que le es propio, no de todo lo que toca.</p>
 */
@Epic("Normalization")
@Feature("Rubro")
@DisplayName("RubroResolver — el rubro oficina")
class RubroResolverOficinaTest {

    /** Un registry con INPRO (rubro forzado) y un sitio de moda sin forzar. */
    private static RubroResolver resolver() {
        Map<String, SiteRegistry.Sitio> cache = new LinkedHashMap<>();
        cache.put("inpro", new SiteRegistry.Sitio(
                "Inpro", "inpro", "inpro", false, "oficina", "config"));
        cache.put("freres", new SiteRegistry.Sitio(
                "Freres", "freres", "shopify", false, null, "config"));
        return new RubroResolver(SiteRegistry.forTesting(cache));
    }

    @Test
    @DisplayName("Un sitio con rubro_forzado='oficina' clasifica sus productos como oficina")
    void sitioForzadoAOficinaDaOficina() {
        assertThat(resolver().resolver("inpro", "Silla", null)).isEqualTo("oficina");
        assertThat(resolver().resolver("inpro", "Escritorio", null)).isEqualTo("oficina");
        assertThat(resolver().resolver("inpro", "Iluminación", null)).isEqualTo("oficina");
    }

    @Test
    @DisplayName("Una categoría que el clasificador no resolvió sigue siendo oficina en un sitio de oficina")
    void categoriaOtrosEnSitioDeOficinaSigueSiendoOficina() {
        assertThat(resolver().resolver("inpro", "Otros", null)).isEqualTo("oficina");
        assertThat(resolver().resolver("inpro", "", null)).isEqualTo("oficina");
    }

    @Test
    @DisplayName("Una remera vendida por una tienda de oficina es indumentaria, no oficina")
    void productoTextilEnSitioDeOficinaSigueSiendoIndumentaria() {
        assertThat(resolver().resolver("inpro", "Remera", null)).isEqualTo("indumentaria");
        assertThat(resolver().resolver("inpro", "Zapatilla Running", null)).isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("Un suplemento gana sobre el rubro forzado del sitio, igual que con tecnologia")
    void suplementoGanaSobreElRubroForzado() {
        assertThat(resolver().resolver("inpro", "Creatina", null)).isEqualTo("suplementos");
    }

    @Test
    @DisplayName("Sin rubro forzado, una categoría de oficina no arrastra el rubro por sí sola")
    void categoriaDeOficinaSinSitioForzadoNoInventaElRubro() {
        assertThat(resolver().resolver("freres", "Silla", null))
                .as("el rubro lo declara el sitio; la categoria sola no alcanza")
                .isEqualTo("indumentaria");
        assertThat(resolver().resolver("freres", "Silla", "indumentaria"))
                .isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("Un sitio desconocido no se contagia de oficina")
    void sitioDesconocidoNoEsOficina() {
        assertThat(resolver().resolver("noexiste", "Silla", null)).isNotEqualTo("oficina");
    }
}
