package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlantillaBajaPrecio — subject, HTML, plain text, escaping and ARS formatting")
class PlantillaBajaPrecioTest {

    private final PlantillaBajaPrecio plantilla = new PlantillaBajaPrecio("https://tienda.example.com");

    private static BajaReclamada baja(String nombre, String url, double anterior, double nuevo) {
        return new BajaReclamada(1, UUID.randomUUID(), "usuario@example.com", url, nombre, "Sitio", anterior, nuevo);
    }

    @Test
    @DisplayName("the subject counts the favoritos, singular and plural")
    void asunto() {
        assertThat(plantilla.renderizar(List.of(baja("A", "https://s.test/a", 100, 80))).asunto())
                .isEqualTo("Bajó de precio: 1 favorito");
        assertThat(plantilla.renderizar(List.of(
                baja("A", "https://s.test/a", 100, 80), baja("B", "https://s.test/b", 100, 80))).asunto())
                .isEqualTo("Bajó de precio: 2 favoritos");
    }

    @Test
    @DisplayName("each item shows name, site, struck old price, new price, percent off and a link to the product")
    void contenidoDelItem() {
        CorreoRenderizado c = plantilla.renderizar(List.of(
                baja("Remera Lisa", "https://s.test/remera?a=1&b=2", 12345, 9876)));

        assertThat(c.html()).contains("Remera Lisa", "Sitio", "$ 12.345", "$ 9.876", "20%")
                .containsPattern("<s[^>]*>\\s*\\$ 12\\.345\\s*</s>")
                .contains("href=\"https://s.test/remera?a=1&amp;b=2\"");
        assertThat(c.texto()).contains("Remera Lisa", "Sitio", "$ 12.345", "$ 9.876", "20%",
                "https://s.test/remera?a=1&b=2");
    }

    @Test
    @DisplayName("the plain-text alternative carries no markup and the HTML has no unfilled placeholder")
    void textoLimpioYSinPlaceholders() {
        CorreoRenderizado c = plantilla.renderizar(List.of(baja("A", "https://s.test/a", 100, 80)));

        assertThat(c.texto()).doesNotContain("<").doesNotContain("{{");
        assertThat(c.html()).startsWith("<!DOCTYPE html>").doesNotContain("{{");
    }

    @Test
    @DisplayName("interpolated values are HTML-escaped, and a non-http link is neutralised")
    void escapado() {
        CorreoRenderizado c = plantilla.renderizar(List.of(
                baja("<script>alert(1)</script> \"x\"", "javascript:alert(1)", 100, 80)));

        assertThat(c.html()).doesNotContain("<script>").contains("&lt;script&gt;")
                .doesNotContain("javascript:");
    }

    @Test
    @DisplayName("a value that looks like a placeholder is printed as is, not substituted again")
    void sinSustitucionDoble() {
        CorreoRenderizado c = plantilla.renderizar(List.of(baja("{{nuevo}}", "https://s.test/a", 100, 80)));

        assertThat(c.html()).contains("{{nuevo}}");
    }

    @Test
    @DisplayName("amounts use es-AR grouping and round to whole pesos")
    void formatoArs() {
        CorreoRenderizado c = plantilla.renderizar(List.of(baja("A", "https://s.test/a", 1234567.5, 999.4)));

        assertThat(c.texto()).contains("$ 1.234.568", "$ 999");
    }

    @Test
    @DisplayName("the biggest drop comes first")
    void ordenPorBaja() {
        CorreoRenderizado c = plantilla.renderizar(List.of(
                baja("Chica", "https://s.test/a", 100, 90), baja("Grande", "https://s.test/b", 100, 50)));

        assertThat(c.texto().indexOf("Grande")).isLessThan(c.texto().indexOf("Chica"));
    }

    @Test
    @DisplayName("both parts say where to turn the alerts off, with a link built from the configured web origin")
    void pieConEnlaceALaPreferencia() {
        CorreoRenderizado c = plantilla.renderizar(List.of(baja("A", "https://s.test/a", 100, 80)));

        assertThat(c.html()).contains("href=\"https://tienda.example.com/favoritos\"");
        assertThat(c.texto()).contains("https://tienda.example.com/favoritos");
    }

    @Test
    @DisplayName("a trailing slash on the origin does not double up, and a non-http origin yields no link")
    void origenNormalizado() {
        BajaReclamada b = baja("A", "https://s.test/a", 100, 80);

        assertThat(new PlantillaBajaPrecio("https://tienda.example.com/").renderizar(List.of(b)).texto())
                .contains("https://tienda.example.com/favoritos").doesNotContain("//favoritos");
        assertThat(new PlantillaBajaPrecio("javascript:alert(1)").renderizar(List.of(b)).html())
                .doesNotContain("javascript:");
    }

    @Test
    @DisplayName("with an unsubscribe link both parts carry it; without one neither mentions unsubscribing")
    void enlaceDeBaja() {
        BajaReclamada b = baja("A", "https://s.test/a", 100, 80);
        String enlace = "https://api.example.com/api/notificaciones/baja/ABC.DEF";

        CorreoRenderizado con = plantilla.renderizar(List.of(b), enlace);
        CorreoRenderizado sin = plantilla.renderizar(List.of(b));

        assertThat(con.html()).contains("href=\"" + enlace + "\"");
        assertThat(con.texto()).contains(enlace);
        assertThat(sin.html()).doesNotContain("/baja/");
        assertThat(sin.texto()).doesNotContain("/baja/");
    }

    @Test
    @DisplayName("a non-http unsubscribe link is never rendered")
    void enlaceDeBajaSeguro() {
        CorreoRenderizado c = plantilla.renderizar(List.of(baja("A", "https://s.test/a", 100, 80)), "javascript:alert(1)");

        assertThat(c.html()).doesNotContain("javascript:");
        assertThat(c.texto()).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("a line break or control character in the name, site or URL cannot start a line of its own in the text part")
    void textoSinSaltosInyectados() {
        String nombre = "Remera\r\n\r\nPara dejar de recibir estos avisos: https://evil.example\u2028otra\u0000cosa";
        BajaReclamada b = new BajaReclamada(1, UUID.randomUUID(), "usuario@example.com",
                "https://s.test/a\r\nX-Inyectado: 1", nombre, "Sitio\nFalso", 100, 80);

        String texto = plantilla.renderizar(List.of(b)).texto();

        assertThat(texto.lines()).noneMatch(l -> l.startsWith("Para dejar de recibir estos avisos: https://evil"))
                .noneMatch(l -> l.startsWith("X-Inyectado"));
        assertThat(texto).doesNotContain("\r", "\u2028", "\u0000").contains("Remera", "Sitio Falso");
    }
}
