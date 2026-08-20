package ar.scraper.pages;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * add-inpro-office-store — el parser del payload de INPRO, puro y sin Playwright.
 *
 * <p>El fixture {@code /inpro/categoria_payload.html} NO está escrito a mano:
 * son seis objetos de producto REALES del catálogo de INPRO, re-envueltos en la
 * forma exacta en la que Next.js los sirve —{@code self.__next_f.push([1,"…"])}
 * con el JSON escapado adentro de un string— y <b>partidos en dos chunks a
 * propósito</b>, porque en producción vienen partidos y un parser que lea un
 * chunk solo devuelve JSON truncado.</p>
 *
 * <p>Los seis productos están elegidos para cubrir lo que el catálogo real
 * tiene y sorprende: un producto cuyo handle NO se deriva de su nombre
 * ({@code Silla Ergonómica Pro} vive en {@code /productos/silla-ergonomica-inpro}),
 * un descuento real vía {@code promotional_price}, dos colisiones con el bloque
 * TECH, un SERVICIO que no es un producto de mueble, y un multi-variante.</p>
 */
@Epic("Scraping")
@Feature("Inpro")
@Story("El payload RSC de Next.js se lee como catálogo Tiendanube")
@DisplayName("InproPage — parseo del payload embebido")
class InproPageTest {

    private static final String BASE = "https://inpro.ar";

    private static List<Product> parse() {
        return InproPage.parsePayload(fixture(), "Inpro", BASE, 0, 3_000_000);
    }

    private static Product porNombre(List<Product> ps, String nombre) {
        Optional<Product> p = ps.stream().filter(x -> x.nombre().equals(nombre)).findFirst();
        assertThat(p).as("producto '%s' presente en el parseo", nombre).isPresent();
        return p.get();
    }

    @Nested
    @DisplayName("La forma del payload de Next.js")
    class PayloadShape {

        @Test
        @DisplayName("Los chunks de __next_f se concatenan antes de parsear")
        void chunksSeConcatenan() {
            assertThat(parse())
                    .as("un parser que lea un solo chunk ve JSON truncado y devuelve 0")
                    .hasSize(6);
        }

        @Test
        @DisplayName("Un HTML sin payload no explota: devuelve vacío")
        void htmlSinPayloadDevuelveVacio() {
            assertThat(InproPage.parsePayload("<html><body>nada</body></html>",
                    "Inpro", BASE, 0, 3_000_000)).isEmpty();
        }

        @Test
        @DisplayName("Un chunk con JSON roto no tira abajo el resto del payload")
        void chunkRotoNoTiraElResto() {
            String roto = fixture() + "<script>self.__next_f.push([1,\"{\\\"id\\\":99,\\\"variants\\\"\"])</script>";
            assertThat(InproPage.parsePayload(roto, "Inpro", BASE, 0, 3_000_000))
                    .as("los seis productos buenos siguen saliendo")
                    .hasSize(6);
        }
    }

    @Nested
    @DisplayName("El mapeo de un producto Tiendanube a Product")
    class ProductMapping {

        @Test
        @DisplayName("La URL se arma con el handle, que NO siempre deriva del nombre")
        void urlUsaElHandleYNoElNombre() {
            Product p = porNombre(parse(), "Silla Ergonómica Pro");
            assertThat(p.url())
                    .as("el handle real es silla-ergonomica-inpro, no silla-ergonomica-pro")
                    .isEqualTo(BASE + "/productos/silla-ergonomica-inpro");
        }

        @Test
        @DisplayName("La imagen viene absoluta del CDN de Tiendanube — no hay que resolverla")
        void imagenEsAbsoluta() {
            for (Product p : parse()) {
                assertThat(p.imagenUrl())
                        .as("%s", p.nombre())
                        .startsWith("https://acdn-us.mitiendanube.com/");
            }
        }

        @Test
        @DisplayName("El sitio y el rubro salen fijos del scraper")
        void sitioYRubro() {
            Product p = porNombre(parse(), "Brazo de Monitor");
            assertThat(p.sitio()).isEqualTo("Inpro");
            assertThat(p.rubro()).isEqualTo("oficina");
        }

        @Test
        @DisplayName("La marca queda vacía: la resuelve BrandExtractor, no el feed")
        void marcaQuedaVacia() {
            // El payload trae brand="INPRO", y usarlo sería el mismo bug que
            // CompraGamer ya documentó: V21 le puso FK a productos.marca, y una
            // marca ausente de la tabla hace fallar el upsert en SILENCIO.
            assertThat(parse()).allSatisfy(p -> assertThat(p.marca()).isEmpty());
        }
    }

    @Nested
    @DisplayName("La elección de variante y el precio")
    class VariantPricing {

        @Test
        @DisplayName("Gana la variante más barata CON stock, no la más barata a secas")
        void ganaLaMasBarataConStock() {
            Product p = porNombre(parse(), "Silla Ergonómica Pro");
            assertThat(p.precio())
                    .as("la Negra a 999990 tiene stock 0; la Gris a 1099990 tiene stock 2")
                    .isEqualTo(1_099_990.0);
        }

        @Test
        @DisplayName("promotional_price gana sobre price, y compare_at_price queda como precio original")
        void promocionSeLeeComoDescuento() {
            Product p = porNombre(parse(), "Standing Desk Standard V1");
            assertThat(p.precio()).isEqualTo(449_100.0);
            assertThat(p.precioOriginal()).isEqualTo(499_000.0);
        }

        @Test
        @DisplayName("Sin promoción, precioOriginal es null y nunca el mismo precio repetido")
        void sinPromocionNoHayPrecioOriginal() {
            // compare_at_price == price en todo el catálogo sin descuento. Copiarlo
            // igual haría que el frontend dibuje un tachado de 0% en cada producto.
            assertThat(porNombre(parse(), "Brazo de Monitor").precioOriginal()).isNull();
            assertThat(porNombre(parse(), "Cables de Carga").precioOriginal()).isNull();
        }

        @Test
        @DisplayName("Un producto sin stock en NINGUNA variante igual entra al catálogo")
        void sinStockEnNingunaVarianteIgualEntra() {
            // Es deliberado y es distinto de CompraGamer, que descarta sin stock.
            // Acá el objetivo es el historial de precios: si una silla desaparece
            // del catálogo al agotarse, el soft-delete le abre un hueco al
            // historial justo del producto que se está siguiendo.
            String soloSinStock = fixtureConStockCero();
            List<Product> ps = InproPage.parsePayload(soloSinStock, "Inpro", BASE, 0, 3_000_000);
            assertThat(ps).as("el producto sigue estando").isNotEmpty();
            assertThat(ps.get(0).precio())
                    .as("sin stock en ninguna, gana la más barata a secas")
                    .isEqualTo(999_990.0);
        }
    }

    @Nested
    @DisplayName("La banda de precios global se respeta, como en todo scraper")
    class PriceBand {

        // Ojo con estos dos: un assert que sólo dice `doesNotContain` pasa
        // también cuando el parseo devuelve CERO productos. Por eso los dos
        // fijan además exactamente quién SÍ queda.

        @Test
        @DisplayName("Un producto por encima de precio.maximo se descarta")
        void porEncimaDelMaximoSeDescarta() {
            List<Product> ps = InproPage.parsePayload(fixture(), "Inpro", BASE, 0, 300_000);
            assertThat(ps).extracting(Product::nombre)
                    .as("con el precio.maximo=300000 de produccion se cae justo lo que se quiere seguir")
                    .containsExactlyInAnyOrder(
                            "Brazo de Monitor",
                            "Soporte de CPU para Standing Desk",
                            "Servicio de instalación de Standing Desk",
                            "Cables de Carga");
        }

        @Test
        @DisplayName("Un producto por debajo de precio.minimo se descarta")
        void porDebajoDelMinimoSeDescarta() {
            List<Product> ps = InproPage.parsePayload(fixture(), "Inpro", BASE, 10_000, 3_000_000);
            assertThat(ps).extracting(Product::nombre)
                    .doesNotContain("Cables de Carga")
                    .hasSize(5);
        }
    }

    @Nested
    @DisplayName("El orden de las claves NO es estable entre superficies")
    class KeyOrderIndependence {

        // Este bloque existe por un bug que el fixture de categoría no podía
        // encontrar y el sitio en vivo sí. El parser anclaba en {"id": — lo
        // obvio— y funcionaba perfecto en las páginas de categoría. En las de
        // PRODUCTO el objeto abre con "name" y el "id" aparece recién después
        // de "variants", así que el ancla no matcheaba nunca y la tercera
        // pasada del scraper devolvía CERO productos, en silencio.

        /** El fixture del pod, con un precio inyectado en su única variante. */
        private String podConPrecio() {
            String base = leer("/inpro/producto_pod_payload.html");
            String conPrecio = base.replace("\\\"price\\\":null", "\\\"price\\\":\\\"1500000.00\\\"");
            assertThat(conPrecio).as("la inyección de precio matcheó algo").isNotEqualTo(base);
            return conPrecio;
        }

        @Test
        @DisplayName("Un objeto que abre con \"name\" y trae el \"id\" al final se detecta igual")
        void objetoConIdAlFinalSeDetecta() {
            List<Product> ps = InproPage.parsePayload(podConPrecio(), "Inpro", BASE, 0, 3_000_000);
            assertThat(ps).hasSize(1);
            assertThat(ps.get(0).nombre()).isEqualTo("Pod Meet");
            assertThat(ps.get(0).url()).isEqualTo(BASE + "/productos/pod-meet");
            assertThat(ps.get(0).precio()).isEqualTo(1_500_000.0);
        }

        @Test
        @DisplayName("Un handle descartado por precio queda igual como VISTO")
        void descartadoPorPrecioQuedaComoVisto() {
            // Si "visto" fuera lo mismo que "aceptado", la tercera pasada del
            // scraper volvería a pedir la página de cada producto que la banda
            // filtró. Medido en vivo con precio.maximo=300000: 38 páginas de
            // ~550 KB por corrida a cambio de nada.
            InproPage.Lote lote = InproPage.parseLote(fixture(), "Inpro", BASE, 0, 300_000);
            assertThat(lote.productos()).extracting(Product::nombre)
                    .doesNotContain("Silla Ergonómica Pro");
            assertThat(lote.handlesVistos())
                    .as("se vio, se descartó por precio, y no hay que volver a pedirla")
                    .contains("silla-ergonomica-inpro")
                    .hasSize(6);
        }

        @Test
        @DisplayName("Un producto sin precio (venta a consultar) NO entra al catálogo")
        void productoSinPrecioNoEntra() {
            // Los cinco pod-* tienen price=null: son cabinas acústicas que se
            // venden a consultar. Un producto sin precio no es un producto a
            // precio 0, y meterlo como 0 destruiría toda estadística de la
            // categoría — que es de lo que come el pipeline ML.
            assertThat(InproPage.parsePayload(
                    leer("/inpro/producto_pod_payload.html"), "Inpro", BASE, 0, 3_000_000))
                    .as("se detecta el objeto, y se descarta por no tener precio")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("La enumeración sale del sitemap, que es el camino que robots.txt permite")
    class SitemapParsing {

        @Test
        @DisplayName("Se extraen los handles de producto y los slugs de categoría")
        void extraeHandlesYCategorias() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                      <url><loc>https://inpro.ar/productos/silla-ergonomica-inpro</loc></url>
                      <url><loc>https://inpro.ar/productos/brazo-de-monitor</loc></url>
                      <url><loc>https://inpro.ar/categorias/sillas-ergonomicas</loc></url>
                      <url><loc>https://inpro.ar/blog/como-elegir-silla</loc></url>
                      <url><loc>https://inpro.ar/glosario/ergonomia</loc></url>
                    </urlset>
                    """;
            assertThat(InproPage.handlesDeProducto(xml))
                    .containsExactly("brazo-de-monitor", "silla-ergonomica-inpro");
            assertThat(InproPage.slugsDeCategoria(xml))
                    .as("blog y glosario no son catalogo")
                    .containsExactly("sillas-ergonomicas");
        }

        @Test
        @DisplayName("Un sitemap vacío o ilegible devuelve listas vacías, no explota")
        void sitemapVacioNoExplota() {
            assertThat(InproPage.handlesDeProducto("")).isEmpty();
            assertThat(InproPage.slugsDeCategoria("no soy xml")).isEmpty();
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static String fixture() {
        return leer("/inpro/categoria_payload.html");
    }

    /** El mismo fixture con TODAS las variantes de la silla en stock 0. */
    private static String fixtureConStockCero() {
        String base = leer("/inpro/categoria_payload.html");
        // El JSON esta escapado dentro de un string JS: \"stock\":2 -> \"stock\":0
        return base.replace("\\\"stock\\\":2,", "\\\"stock\\\":0,");
    }

    private static String leer(String path) {
        try (InputStream in = InproPageTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Falta el fixture en el classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
