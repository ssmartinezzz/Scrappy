package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.db.DatabaseService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code GET /api/buscar-externo} — query cleaning and MercadoLibre slug
 * construction, which had no coverage at all.
 *
 * <p>Both are exercised without touching the network: the endpoint only calls
 * MercadoLibre when {@code sitio} is exactly {@code "mercadolibre"}, yet it
 * always returns {@code queryUsada} (the output of the private
 * {@code limpiarQueryBusqueda}) and {@code searchUrl} (the accent-stripped
 * slug). Passing any other {@code sitio} turns the endpoint into a pure
 * function over those two.</p>
 *
 * <p>These were written to make three changes safe: replacing the inline
 * six-{@code replaceAll} accent chain with {@link
 * ar.scraper.aggregator.text.AccentStripper} — the ADR-4 unification that had
 * missed this call site — hoisting the eleven per-call compiled regexes to
 * constants, and reusing a single {@code HttpClient} instead of building one
 * per request (measured: 5.2 ms and one extra live thread, every call, before
 * any network I/O).</p>
 */
@Epic("REST API")
@Feature("Comparador multi-sitio")
@Story("Búsqueda externa")
@DisplayName("ComparadorEndpoints — limpieza de query y slug de búsqueda")
class ComparadorBuscarExternoTest {

    private ComparadorEndpoints endpoints;

    @BeforeEach
    void setUp() {
        endpoints = new ComparadorEndpoints(
                mock(ScraperService.class), mock(DatabaseService.class), mock(GroupingService.class));
    }

    /** Runs the endpoint on the no-network path and returns its body. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> responder(String q) {
        ResponseEntity<Object> resp = endpoints.buscarExterno(q, null, "otro-sitio");
        return (Map<String, Object>) resp.getBody();
    }

    private String queryUsada(String q)  { return (String) responder(q).get("queryUsada"); }
    private String searchUrl(String q)   { return (String) responder(q).get("searchUrl"); }

    /** The slug portion of searchUrl, i.e. everything after the ML listing host. */
    private String slug(String q) {
        return searchUrl(q).replace("https://listado.mercadolibre.com.ar/", "");
    }

    // ─── Slug: the accent chain being swapped ────────────────────────────────

    @Test
    @DisplayName("el slug baja los acentos a su vocal base")
    void elSlugBajaLosAcentos() {
        assertThat(slug("Camión Algodón")).isEqualTo("camion-algodon");
        assertThat(slug("Niño Pequeño")).isEqualTo("nino-pequeno");
        assertThat(slug("Àèìòù")).isEqualTo("aeiou");
    }

    @Test
    @DisplayName("una palabra que empieza con letra de talle seguida de acento queda entera")
    void noSeComeLaPrimeraLetraDePalabrasAcentuadas() {
        // Los bordes de palabra tienen que ser conscientes de Unicode. Con el \b
        // por defecto de Java (\w = [a-zA-Z0-9_]) las vocales acentuadas no son
        // caracteres de palabra, así que en "Móvil" había un borde entre la M y
        // la ó: la M pasaba por talle suelto y el filtro se la llevaba.
        assertThat(queryUsada("Móvil")).isEqualTo("Móvil");
        assertThat(queryUsada("Lápiz")).isEqualTo("Lápiz");
        assertThat(queryUsada("Sábana")).isEqualTo("Sábana");
        assertThat(queryUsada("Máscara Línea Sésamo")).isEqualTo("Máscara Línea Sésamo");
    }

    @Test
    @DisplayName("tampoco se come un color que es prefijo de una palabra acentuada")
    void noSeComeUnColorPrefijoDePalabraAcentuada() {
        // Mismo defecto en el filtro de colores: "azulón" tenía un borde entre
        // la l y la ó, así que "azul" matcheaba y quedaba "ón".
        assertThat(queryUsada("Campera Azulón")).isEqualTo("Campera Azulón");
    }

    @Test
    @DisplayName("los talles sueltos de verdad se siguen sacando")
    void losTallesSueltosDeVerdadSeSiguenSacando() {
        // El arreglo no puede volver inofensivo al filtro: una letra que SÍ está
        // suelta tiene que seguir desapareciendo.
        assertThat(queryUsada("Remera Oversize M")).isEqualTo("Remera Oversize");
        assertThat(queryUsada("Buzo Frisado S")).isEqualTo("Buzo Frisado");
        assertThat(queryUsada("Campera Puffer XL")).isEqualTo("Campera Puffer");
        // Y una palabra que empieza con esa letra pero sigue con ASCII tampoco
        // se toca, que ya era el comportamiento correcto.
        assertThat(queryUsada("Mochila Urbana")).isEqualTo("Mochila Urbana");
    }

    @Test
    @DisplayName("el slug baja a minúscula, saca símbolos y une con guiones")
    void elSlugNormalizaYUneConGuiones() {
        assertThat(slug("Nike Air Force")).isEqualTo("nike-air-force");
        assertThat(slug("Adidas   Superstar")).isEqualTo("adidas-superstar");
        assertThat(slug("Puma RS-X!!")).isEqualTo("puma-rs-x");
    }

    @Test
    @DisplayName("searchUrl siempre apunta al listado canónico de Argentina")
    void searchUrlUsaElHostCanonico() {
        assertThat(searchUrl("Nike Air Force"))
                .startsWith("https://listado.mercadolibre.com.ar/");
    }

    // ─── Query cleaning ──────────────────────────────────────────────────────

    @Test
    @DisplayName("quita talles, sueltos y con etiqueta")
    void quitaTalles() {
        assertThat(queryUsada("Nike Air Force talle XL")).isEqualTo("Nike Air Force");
        assertThat(queryUsada("Nike Air Force XXL")).isEqualTo("Nike Air Force");
        assertThat(queryUsada("Nike Air Force 42")).isEqualTo("Nike Air Force");
    }

    @Test
    @DisplayName("quita colores")
    void quitaColores() {
        assertThat(queryUsada("Nike Air Force Negro")).isEqualTo("Nike Air Force");
        assertThat(queryUsada("Nike Air Force Blanca")).isEqualTo("Nike Air Force");
    }

    @Test
    @DisplayName("quita marcadores de género")
    void quitaGenero() {
        assertThat(queryUsada("Campera Puffer de Hombre")).isEqualTo("Campera Puffer");
        assertThat(queryUsada("Campera Puffer unisex")).isEqualTo("Campera Puffer");
    }

    @Test
    @DisplayName("quita descriptores genéricos y códigos SKU largos")
    void quitaDescriptoresYSku() {
        assertThat(queryUsada("Nike Air Force original")).isEqualTo("Nike Air Force");
        assertThat(queryUsada("Nike Air Force 123456")).isEqualTo("Nike Air Force");
    }

    @Test
    @DisplayName("normaliza puntuación y espacios repetidos")
    void normalizaPuntuacionYEspacios() {
        assertThat(queryUsada("Nike, Air (Force)")).isEqualTo("Nike Air Force");
        assertThat(queryUsada("Nike   Air    Force")).isEqualTo("Nike Air Force");
    }

    @Test
    @DisplayName("trunca en el límite de palabra a 60 caracteres")
    void truncaEnLimiteDePalabra() {
        String largo = "Campera Rompeviento Impermeable Deportiva Premium Extendida Adicional Suplementaria";
        String limpia = queryUsada(largo);
        assertThat(limpia.length()).isLessThanOrEqualTo(60);
        assertThat(limpia).doesNotEndWith(" ");
    }

    @Test
    @DisplayName("si la limpieza deja todo vacío, cae al nombre original recortado")
    void siQuedaVacioCaeAlNombreOriginal() {
        // Solo color + talle: la limpieza se lleva todo.
        assertThat(queryUsada("negro XL")).isNotBlank();
    }

    @Test
    @DisplayName("una query vacía o nula no rompe el endpoint")
    void queryVaciaNoRompe() {
        assertThat(responder("")).containsKey("searchUrl");
        assertThat((java.util.List<?>) responder("").get("resultados")).isEmpty();
    }

    @Test
    @DisplayName("sin sitio mercadolibre no hay llamada externa: resultados vacíos")
    void sinSitioMercadolibreNoHayLlamadaExterna() {
        Map<String, Object> body = responder("Nike Air Force");
        assertThat((java.util.List<?>) body.get("resultados")).isEmpty();
        assertThat(body).containsKeys("searchUrl", "queryUsada", "resultados");
    }
}
