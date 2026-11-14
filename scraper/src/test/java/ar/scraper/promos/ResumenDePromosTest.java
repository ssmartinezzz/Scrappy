package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoVigente;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResumenDePromos — usefulness order, best promo and medio priority")
class ResumenDePromosTest {

    private static PromoVigente cuotas(String medio, int n) {
        return new PromoVigente("S", medio, TipoMedio.BANCO, null, TipoPromo.CUOTAS_SIN_INTERES, n, null,
                Alcance.SITIO, null, null, null, null);
    }

    private static PromoVigente descuento(String medio, String pct) {
        return new PromoVigente("S", medio, TipoMedio.MEDIO, null, TipoPromo.DESCUENTO, null, new BigDecimal(pct),
                Alcance.SITIO, null, null, null, null);
    }

    private static PromoVigente recargo(String medio, String pct) {
        return new PromoVigente("S", medio, TipoMedio.MEDIO, null, TipoPromo.RECARGO, null, new BigDecimal(pct),
                Alcance.SITIO, null, null, null, null);
    }

    @Test
    @DisplayName("discounts first by pct, then interest-free by installments, surcharges last")
    void ordersByUsefulness() {
        var lista = List.of(recargo("Tarjeta de crédito", "10"), cuotas("Galicia", 3), descuento("Transferencia", "10"),
                cuotas("Santander", 12), descuento("Efectivo", "15"));

        assertThat(ResumenDePromos.ordenar(lista)).extracting(PromoVigente::medio)
                .containsExactly("Efectivo", "Transferencia", "Santander", "Galicia", "Tarjeta de crédito");
    }

    @Test
    @DisplayName("ties break by medio priority (Mercado Pago, Transferencia, Santander) then by name")
    void tiesBreakByPriority() {
        var lista = List.of(cuotas("Galicia", 6), cuotas("Santander", 6), cuotas("Mercado Pago", 6), cuotas("BBVA", 6));

        assertThat(ResumenDePromos.ordenar(lista)).extracting(PromoVigente::medio)
                .containsExactly("Mercado Pago", "Santander", "BBVA", "Galicia");
    }

    @Test
    @DisplayName("the best promo is the first of the order and never a surcharge")
    void bestIsFirstAndNeverSurcharge() {
        assertThat(ResumenDePromos.mejor(List.of(cuotas("Galicia", 3), descuento("Transferencia", "10"))))
                .get().extracting(PromoVigente::medio).isEqualTo("Transferencia");
        assertThat(ResumenDePromos.mejor(List.of(recargo("Tarjeta de crédito", "10")))).isEmpty();
        assertThat(ResumenDePromos.mejor(List.of())).isEmpty();
    }

    @Test
    @DisplayName("medios are distinct, priority ones first, the rest alphabetical; a surcharge still counts as accepted")
    void mediosPriorityThenAlphabetical() {
        var lista = List.of(cuotas("Galicia", 3), cuotas("Galicia", 6), descuento("Transferencia", "10"),
                cuotas("Santander", 12), cuotas("BBVA", 3), recargo("Tarjeta de crédito", "10"),
                cuotas("Mercado Pago", 3));

        assertThat(ResumenDePromos.medios(lista)).containsExactly(
                "Mercado Pago", "Transferencia", "Santander", "BBVA", "Galicia", "Tarjeta de crédito");
    }
}
