package ar.scraper.pages;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping Engine")
@Feature("TiendaNube Parsing")
@Story("Monkyforce collections")
@DisplayName("MonkyforcePage — collections carry gender and category hints")
class MonkyforceColeccionesTest {

    private static final String BASE = "https://www.monkyforce.com/productos/";
    private static final String REMERAS = "https://www.monkyforce.com/indumentaria/remeras/";
    private static final String TOP = "https://www.monkyforce.com/indumentaria-mujer/remeras-top/";

    private static Product p(String nombre, String genero) {
        return Product.builder().sitio("Monkyforce").nombre(nombre).precio(1000)
                .url("https://www.monkyforce.com/productos/" + nombre.toLowerCase().replace(' ', '-') + "/")
                .categoria("").genero(genero).talles(List.of()).build();
    }

    private static final class Fake extends MonkyforcePage {
        final Map<String, List<Product>> porUrl = new LinkedHashMap<>();
        final List<String> visitadas = new ArrayList<>();

        Fake() {
            super(null, 0, "Monkyforce", BASE, 0, 1_000_000, List.of());
        }

        @Override
        protected boolean usaApi() {
            return false;
        }

        @Override
        List<Product> scrapeJs(String url) {
            visitadas.add(url);
            return porUrl.getOrDefault(url, List.of());
        }
    }

    private static Product por(List<Product> r, String nombre) {
        return r.stream().filter(x -> x.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("hint fills genero and categoria when the card gave none")
    void hintLlenaLoQueLaTarjetaNoDio() {
        Fake f = new Fake();
        f.porUrl.put(REMERAS, List.of(p("Never Give Up", "")));
        f.porUrl.put(TOP, List.of(p("Soft Gear", "")));

        List<Product> r = f.scrapeAll();

        assertThat(por(r, "Never Give Up").genero()).isEqualTo("hombre");
        assertThat(por(r, "Never Give Up").categoria()).isEqualTo("Remera");
        assertThat(por(r, "Soft Gear").genero()).isEqualTo("mujer");
        assertThat(por(r, "Soft Gear").categoria()).isEqualTo("Remera");
    }

    @Test
    @DisplayName("a genero the card derived from a real word is not overwritten")
    void generoDeLaTarjetaGana() {
        Fake f = new Fake();
        f.porUrl.put(REMERAS, List.of(p("Remera Mujer", "mujer")));

        assertThat(f.scrapeAll().get(0).genero()).isEqualTo("mujer");
    }

    @Test
    @DisplayName("collections without a categoria hint leave it blank")
    void coleccionSinCategoria() {
        Fake f = new Fake();
        f.porUrl.put("https://www.monkyforce.com/indumentaria-mujer/shorts1/", List.of(p("Bras Cruzado", "")));

        Product x = f.scrapeAll().get(0);
        assertThat(x.genero()).isEqualTo("mujer");
        assertThat(x.categoria()).isEmpty();
    }

    @Test
    @DisplayName("a product in two collections and in the base appears once, hinted, first-seen order")
    void dedupPorUrlGanaLaCopiaConHint() {
        Fake f = new Fake();
        f.porUrl.put(REMERAS, List.of(p("Never Give Up", ""), p("Otra", "")));
        f.porUrl.put(TOP, List.of(p("Never Give Up", "")));
        f.porUrl.put(BASE, List.of(p("Never Give Up", ""), p("Otra", "")));

        List<Product> r = f.scrapeAll();

        assertThat(r).extracting(Product::nombre).containsExactly("Never Give Up", "Otra");
        assertThat(por(r, "Never Give Up").genero()).isEqualTo("hombre");
    }

    @Test
    @DisplayName("a product only in /productos/ still appears, unhinted")
    void soloEnElCatalogoBase() {
        Fake f = new Fake();
        f.porUrl.put(REMERAS, List.of(p("Never Give Up", "")));
        f.porUrl.put(BASE, List.of(p("Never Give Up", ""), p("Gift Card", "")));

        List<Product> r = f.scrapeAll();

        assertThat(r).extracting(Product::nombre).containsExactly("Never Give Up", "Gift Card");
        assertThat(por(r, "Gift Card").genero()).isEmpty();
        assertThat(f.visitadas.get(f.visitadas.size() - 1)).isEqualTo(BASE);
    }
}
