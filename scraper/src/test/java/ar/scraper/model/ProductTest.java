package ar.scraper.model;

import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalCompra;
import ar.scraper.model.Product.SenalFinanciacion;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code cantidadUnidades} component added to
 * {@link Product} as part of pack/combo pricing detection (PR 1).
 *
 * Covers: the canonical 17-arg constructor sets the field correctly,
 * every legacy overload defaults it to 1, and {@code esPack()} reflects it.
 */
@Epic("Domain Model")
@Feature("Product")
@DisplayName("Product — cantidadUnidades / pack detection")
class ProductTest {

    private static final List<String> TALLES = List.of("M");

    @Test
    void canonicalConstructorSetsCantidadUnidades() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Pack x3 Remeras")
                .precio(15000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .cantidadUnidades(3)
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(3);
        assertThat(p.esPack()).isTrue();
    }

    @Test
    void canonicalConstructorWithQuantityOneIsNotPack() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera básica")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(1);
        assertThat(p.esPack()).isFalse();
    }

    @Test
    void legacyNineArgConstructorDefaultsToOne() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(1);
        assertThat(p.esPack()).isFalse();
    }

    @Test
    void legacyTenArgConstructorWithMlScoreDefaultsToOne() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void legacyElevenArgConstructorWithMarcaDefaultsToOne() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void legacyThirteenArgConstructorWithRubroGymratDefaultsToOne() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(true)
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void legacyFifteenArgConstructorWithSenalDefaultsToOne() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(true)
                .marcaPremium(false)
                .senal(SenalCompra.EMPTY)
                .build();

        assertThat(p.cantidadUnidades()).isEqualTo(1);
        assertThat(p.esPack()).isFalse();
    }

    @Test
    void esPackIsTrueOnlyWhenCantidadUnidadesGreaterThanOne() {
        Product pack = Product.builder()
                .sitio("Sitio")
                .nombre("Combo x2")
                .precio(20000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Conjunto")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .cantidadUnidades(2)
                .build();
        Product single = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .ml(MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .build();

        assertThat(pack.esPack()).isTrue();
        assertThat(single.esPack()).isFalse();
    }

    // ── precioOriginal is a Double: NULL is "no opinion", not a sentinel
    //    string (D1/DD1). tieneDescuento() reflects that directly: a non-null
    //    value means the original price parsed, nothing more. ────────────────

    @Test
    @DisplayName("tieneDescuento() es true cuando precioOriginal parseó a un Double")
    void tieneDescuentoEsTrueConPrecioOriginalNoNulo() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(8000.0)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .build();

        assertThat(p.tieneDescuento()).isTrue();
        assertThat(p.precioOriginal()).isEqualTo(8000.0);
    }

    @Test
    @DisplayName("tieneDescuento() es false cuando precioOriginal es null (no parseó / no había)")
    void tieneDescuentoEsFalseConPrecioOriginalNulo() {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(5000)
                .precioOriginal(null)
                .url("http://x")
                .imagenUrl("http://img")
                .categoria("Remera")
                .genero("hombre")
                .talles(TALLES)
                .build();

        assertThat(p.tieneDescuento()).isFalse();
        assertThat(p.precioOriginal()).isNull();
    }
}
