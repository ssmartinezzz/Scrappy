package ar.scraper.ml;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("The ML input is streamed to its file")
@DisplayName("MlEnricher.productosParaMl — the streamed input is the document the tree used to produce")
class MlProductosParaMlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MlEnricher enricher = new MlEnricher();

    /** The serialization as it was before it streamed: a Jackson tree rendered to a string. */
    private static String referencia(List<Product> productos) throws Exception {
        var arr = MAPPER.createArrayNode();
        for (Product p : productos) {
            var n = MAPPER.createObjectNode();
            n.put("url",            p.url()            != null ? p.url()            : "");
            n.put("nombre",         p.nombre());
            n.put("precio",         p.precio());
            n.put("precioOriginal", p.precioOriginal());
            n.put("categoria",      p.categoria()      != null ? p.categoria()      : "");
            n.put("genero",         p.genero()         != null ? p.genero()         : "");
            n.put("subCategoria",   p.subCategoria()   != null ? p.subCategoria()   : "");
            n.put("sitio",          p.sitio());
            n.put("marca",          p.marca()          != null ? p.marca()          : "");
            n.put("img",            p.imagenUrl()      != null ? p.imagenUrl()      : "");
            arr.add(n);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private byte[] escrito(List<Product> productos) throws IOException {
        var out = new ByteArrayOutputStream();
        enricher.productosParaMl(productos).escribirEn(out);
        return out.toByteArray();
    }

    private static List<Product> variados() {
        List<Product> lista = new ArrayList<>();
        lista.add(Product.builder().sitio("Sporting").nombre("Zapatillas \"Running\" Galaxy 7 \\ ñandú")
                .precio(89999.5).precioOriginal(120000.0).url("https://s.com/a?x=1&y=2")
                .imagenUrl("https://s.com/a.jpg").categoria("Zapatillas").genero("hombre")
                .subCategoria("Running").marca("Nike").build());
        lista.add(Product.builder().sitio("Venex").nombre("Notebook 15\" café ☕ 𝄞 日本語").precio(1.0E10)
                .precioOriginal(null).url(null).imagenUrl(null).categoria(null).genero(null)
                .subCategoria(null).marca(null).build());
        lista.add(Product.builder().sitio("Sitio").nombre("x").precio(0.1 + 0.2).precioOriginal(0.0)
                .url("").imagenUrl("").categoria("").genero("").subCategoria("").marca("").build());
        lista.add(Product.builder().sitio("Línea\ncon\tcontrol \u0001").nombre("ctl \u001f").precio(-3)
                .precioOriginal(Double.NaN).url("u").build());
        return lista;
    }

    @Test
    @DisplayName("the bytes are exactly the old document, for nulls, escapes, non-BMP text and odd numbers")
    void bytesAreTheOldDocument() throws Exception {
        List<Product> productos = variados();

        assertThat(new String(escrito(productos), StandardCharsets.UTF_8)).isEqualTo(referencia(productos));
        assertThat(escrito(productos)).isEqualTo(referencia(productos).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a lone surrogate is an I/O error, as it was when the string was encoded to the file")
    void loneSurrogateIsAnError() {
        List<Product> roto = List.of(Product.builder().sitio("Sitio").nombre("roto \uD800 fin").precio(1).url("u").build());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> escrito(roto)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an empty catalogue is an empty array, as before")
    void emptyCatalogue() throws Exception {
        assertThat(new String(escrito(List.of()), StandardCharsets.UTF_8)).isEqualTo("[]");
    }

    @Test
    @DisplayName("serializarProductos returns the same document")
    void stringFormIsTheSameDocument() throws Exception {
        assertThat(enricher.serializarProductos(variados())).isEqualTo(referencia(variados()));
    }

    @Test
    @DisplayName("writing does not close the file the runner still owns")
    void doesNotCloseTheTarget() throws Exception {
        var cerrado = new boolean[1];
        var out = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { cerrado[0] = true; super.close(); }
        };

        enricher.productosParaMl(variados()).escribirEn(out);

        assertThat(cerrado[0]).isFalse();
    }
}
