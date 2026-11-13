package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import ar.scraper.model.promo.PromosDeCorrida;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Promos")
@Feature("Persistence")
@DisplayName("FilasDePromo — product and site promos folded into sp_upsert_promos rows")
class FilasDePromoTest {

    private static PromoFinanciacion cuotas(Alcance alcance, int n) {
        return new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.CUOTAS_SIN_INTERES, n, null,
                alcance, null, null, "texto");
    }

    private static PromoFinanciacion descuento(Alcance alcance, String pct) {
        return new PromoFinanciacion("Transferencia", TipoMedio.MEDIO, TipoPromo.DESCUENTO, null,
                new BigDecimal(pct), alcance, null, null, "texto");
    }

    private static Product producto(String sitio, String url, PromoFinanciacion... promos) {
        return Product.builder().sitio(sitio).nombre("SSD").precio(10).url(url)
                .promos(List.of(promos)).build();
    }

    @Test
    @DisplayName("the same promo on two products of one site is one row with both urls")
    void sharedPromoBecomesOneRowWithTwoUrls() {
        PromoFinanciacion p = cuotas(Alcance.PRODUCTO, 6);

        List<FilasDePromo.Fila> filas = FilasDePromo.agrupar(
                List.of(producto("A", "u1", p), producto("A", "u2", p)), PromosDeCorrida.VACIO);

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).sitio()).isEqualTo("A");
        assertThat(filas.get(0).urls()).containsExactly("u1", "u2");
    }

    @Test
    @DisplayName("the same promo on two sites stays two rows")
    void samePromoOnTwoSitesIsTwoRows() {
        PromoFinanciacion p = cuotas(Alcance.PRODUCTO, 6);

        List<FilasDePromo.Fila> filas = FilasDePromo.agrupar(
                List.of(producto("A", "u1", p), producto("B", "u2", p)), PromosDeCorrida.VACIO);

        assertThat(filas).extracting(FilasDePromo.Fila::sitio).containsExactly("A", "B");
    }

    @Test
    @DisplayName("promos that differ in the natural key are separate rows")
    void differentNaturalKeysAreSeparateRows() {
        List<FilasDePromo.Fila> filas = FilasDePromo.agrupar(
                List.of(producto("A", "u1", cuotas(Alcance.PRODUCTO, 3), cuotas(Alcance.PRODUCTO, 6))),
                PromosDeCorrida.VACIO);

        assertThat(filas).hasSize(2);
    }

    @Test
    @DisplayName("site-level promos go as rows without urls")
    void siteLevelPromosHaveNoUrls() {
        PromosDeCorrida promos = new PromosDeCorrida(
                Map.of("A", List.of(descuento(Alcance.SITIO, "10"))), Set.of("A"));

        List<FilasDePromo.Fila> filas = FilasDePromo.agrupar(List.of(), promos);

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).urls()).isEmpty();
        assertThat(filas.get(0).promo().alcance()).isEqualTo(Alcance.SITIO);
    }

    @Test
    @DisplayName("a site-scoped promo riding on a product is not linked to it")
    void siteScopedPromoOnAProductIsIgnored() {
        assertThat(FilasDePromo.agrupar(
                List.of(producto("A", "u1", descuento(Alcance.SITIO, "10"))), PromosDeCorrida.VACIO))
                .isEmpty();
    }

    @Test
    @DisplayName("a product without a url cannot be linked and adds no row")
    void productWithoutUrlIsSkipped() {
        assertThat(FilasDePromo.agrupar(
                List.of(producto("A", " ", cuotas(Alcance.PRODUCTO, 6))), PromosDeCorrida.VACIO)).isEmpty();
    }

    @Test
    @DisplayName("no promos anywhere means no rows")
    void noPromosNoRows() {
        assertThat(FilasDePromo.agrupar(List.of(producto("A", "u1")), PromosDeCorrida.VACIO)).isEmpty();
    }
}
