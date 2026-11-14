package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PromoFinanciacion — the record refuses what the SQL CHECKs would refuse")
class PromoFinanciacionTest {

    private static PromoFinanciacion cuotas(Integer cuotas) {
        return new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.CUOTAS_SIN_INTERES, cuotas, null,
                Alcance.SITIO, null, null, null);
    }

    private static PromoFinanciacion pct(TipoPromo tipo, String pct) {
        return new PromoFinanciacion("Transferencia", TipoMedio.MEDIO, tipo, null,
                pct == null ? null : new BigDecimal(pct), Alcance.SITIO, null, null, null);
    }

    @Test
    void cuotasSinInteresRequiereCuotasPositivas() {
        assertThat(cuotas(3).cuotas()).isEqualTo(3);
        assertThatThrownBy(() -> cuotas(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cuotas(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cuotas(-6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descuentoYRecargoRequierenPctEnRangoAbiertoCerrado() {
        for (TipoPromo t : new TipoPromo[] {TipoPromo.DESCUENTO, TipoPromo.RECARGO}) {
            assertThat(pct(t, "10").pct()).isEqualByComparingTo("10");
            assertThat(pct(t, "100").pct()).isEqualByComparingTo("100");
            assertThatThrownBy(() -> pct(t, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pct(t, "0")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pct(t, "-5")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pct(t, "100.01")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void pctSeRedondeaALaEscalaDeLaColumna() {
        assertThat(pct(TipoPromo.DESCUENTO, "12.5").pct().scale()).isEqualTo(2);
        assertThatThrownBy(() -> pct(TipoPromo.DESCUENTO, "0.004")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void camposObligatoriosNoPuedenFaltar() {
        assertThatThrownBy(() -> new PromoFinanciacion(" ", TipoMedio.MEDIO, TipoPromo.DESCUENTO, null,
                BigDecimal.TEN, Alcance.SITIO, null, null, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PromoFinanciacion("Visa", null, TipoPromo.DESCUENTO, null,
                BigDecimal.TEN, Alcance.SITIO, null, null, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PromoFinanciacion("Visa", TipoMedio.TARJETA, null, null,
                BigDecimal.TEN, Alcance.SITIO, null, null, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.DESCUENTO, null,
                BigDecimal.TEN, null, null, null, null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void laIgualdadEsLaClaveNaturalDelIndiceUnico() {
        var a = new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.DESCUENTO, null,
                new BigDecimal("10"), Alcance.SITIO, null, null, "texto uno");
        var b = new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.DESCUENTO, null,
                new BigDecimal("10.00"), Alcance.SITIO, LocalDate.of(2026, 11, 30), null, "texto dos");
        var otroPct = new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.DESCUENTO, null,
                new BigDecimal("15"), Alcance.SITIO, null, null, "texto uno");
        var otroAlcance = new PromoFinanciacion("Visa", TipoMedio.TARJETA, TipoPromo.DESCUENTO, null,
                new BigDecimal("10"), Alcance.PRODUCTO, null, null, "texto uno");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(otroPct).isNotEqualTo(otroAlcance);
    }

    @Test
    void lasEtiquetasSqlCoincidenConLosCheckDeLaMigracion() {
        assertThat(TipoPromo.CUOTAS_SIN_INTERES.sql()).isEqualTo("cuotas_sin_interes");
        assertThat(TipoPromo.DESCUENTO.sql()).isEqualTo("descuento");
        assertThat(TipoPromo.RECARGO.sql()).isEqualTo("recargo");
        assertThat(Alcance.SITIO.sql()).isEqualTo("sitio");
        assertThat(Alcance.PRODUCTO.sql()).isEqualTo("producto");
    }

    private static PromoFinanciacion conRed(String medio, TipoMedio tipoMedio, String red) {
        return new PromoFinanciacion(medio, tipoMedio, red, TipoPromo.CUOTAS_SIN_INTERES, 12, null,
                Alcance.SITIO, null, null, null);
    }

    @Test
    void laRedFormaParteDeLaClaveNatural() {
        assertThat(conRed("Macro", TipoMedio.BANCO, "Visa")).isEqualTo(conRed("Macro", TipoMedio.BANCO, "Visa"));
        assertThat(conRed("Macro", TipoMedio.BANCO, "Visa")).isNotEqualTo(conRed("Macro", TipoMedio.BANCO, "Mastercard"));
        assertThat(conRed("Macro", TipoMedio.BANCO, "Visa")).isNotEqualTo(conRed("Macro", TipoMedio.BANCO, null));
        assertThat(conRed("Macro", TipoMedio.BANCO, "Visa").hashCode())
                .isNotEqualTo(conRed("Macro", TipoMedio.BANCO, null).hashCode());
    }

    @Test
    void unaTarjetaNoPuedeExigirOtraRedNiUnaRedPuedeSerSuPropioMedio() {
        assertThatThrownBy(() -> conRed("Visa", TipoMedio.TARJETA, "Mastercard"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conRed("Macro", TipoMedio.BANCO, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
