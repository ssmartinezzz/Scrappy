package ar.scraper.promos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromoTextParser — Spanish promo text -> promos, abstaining when unsure")
class PromoTextParserTest {

    private static final LocalDate REF = LocalDate.of(2026, 11, 13);

    private static List<PromoFinanciacion> parse(String texto) {
        return PromoTextParser.parsear(texto, Alcance.SITIO, REF);
    }

    private static String resumen(PromoFinanciacion p) {
        return p.medioPago() + "|" + p.tipo() + "|" + p.cuotas() + "|" + p.pct();
    }

    private static List<String> resumenes(String texto) {
        return parse(texto).stream().map(PromoTextParserTest::resumen).toList();
    }

    @Test
    void cuotasSinInteresIgnoraElCuponDePrimeraCompra() {
        assertThat(resumenes("3 CUOTAS SIN INTERÉS. 10% OFF EN TU PRIMERA COMPRA"))
                .containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null");
    }

    @Test
    void descuentoPorTransferencia() {
        assertThat(resumenes("10% DESCUENTO CON TRANSFERENCIA"))
                .containsExactly("Transferencia|DESCUENTO|null|10.00");
        assertThat(resumenes("15% OFF por transferencia"))
                .containsExactly("Transferencia|DESCUENTO|null|15.00");
        assertThat(resumenes("20% de descuento con transferencia."))
                .containsExactly("Transferencia|DESCUENTO|null|20.00");
        assertThat(resumenes("Cuenta con soporte lumbar y un mecanismo. 20% de descuento con transferencia."))
                .containsExactly("Transferencia|DESCUENTO|null|20.00");
    }

    @Test
    void cuotasSinMedioEsTarjetaDeCreditoGenerica() {
        assertThat(resumenes("9 cuotas sin interés")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|9|null");
    }

    @Test
    void cuotasConBancoOTarjeta() {
        assertThat(resumenes("6 cuotas sin interés con Santander"))
                .containsExactly("Santander|CUOTAS_SIN_INTERES|6|null");
        assertThat(resumenes("Hasta 12 cuotas sin interés con tarjetas Banco Galicia"))
                .containsExactly("Galicia|CUOTAS_SIN_INTERES|12|null");
    }

    @Test
    void variasCuotasPorVariosMediosSonElProductoCartesiano() {
        assertThat(resumenes("3 y 6 cuotas sin interés con Visa y Mastercard"))
                .containsExactlyInAnyOrder(
                        "Visa|CUOTAS_SIN_INTERES|3|null", "Visa|CUOTAS_SIN_INTERES|6|null",
                        "Mastercard|CUOTAS_SIN_INTERES|3|null", "Mastercard|CUOTAS_SIN_INTERES|6|null");
    }

    @Test
    void cadaEventoTomaLosMediosQueLeSiguenHastaElProximo() {
        assertThat(resumenes("3 cuotas sin interés con Visa y 6 cuotas sin interés con Mastercard"))
                .containsExactlyInAnyOrder("Visa|CUOTAS_SIN_INTERES|3|null", "Mastercard|CUOTAS_SIN_INTERES|6|null");
        assertThat(resumenes("10% off con transferencia y 5% off con efectivo"))
                .containsExactlyInAnyOrder("Transferencia|DESCUENTO|null|10.00", "Efectivo|DESCUENTO|null|5.00");
    }

    @Test
    void reintegroYDescuentoConBilletera() {
        assertThat(resumenes("25% de reintegro con Modo")).containsExactly("Modo|DESCUENTO|null|25.00");
        assertThat(resumenes("30% off pagando con Mercado Pago"))
                .containsExactly("Mercado Pago|DESCUENTO|null|30.00");
        assertThat(resumenes("Descuento del 12,5% con Cuenta DNI")).containsExactly("Cuenta DNI|DESCUENTO|null|12.50");
    }

    @Test
    void recargoConTarjetaDeCredito() {
        assertThat(resumenes("10% de recargo con tarjeta de crédito"))
                .containsExactly("Tarjeta de crédito|RECARGO|null|10.00");
    }

    @Test
    void laTarjetaGenericaNoDuplicaAlMedioEspecifico() {
        assertThat(resumenes("6 cuotas sin interés con tarjeta de crédito Visa"))
                .containsExactly("Visa|CUOTAS_SIN_INTERES|6|null");
    }

    @Test
    void cuotasDeUnImporteNoSonSinInteres() {
        assertThat(parse("6 cuotas de $32.666,67*")).isEmpty();
        assertThat(parse("3 cuotas fijas de $10.000")).isEmpty();
        assertThat(resumenes("3 cuotas fijas sin interés")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null");
    }

    @Test
    void variantesDeEscrituraDeSinInteres() {
        assertThat(resumenes("6 CUOTAS SIN INTERES")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null");
        assertThat(resumenes("6 cuotas s/interés")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null");
        assertThat(resumenes("6 cuotas s/ interes")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null");
        assertThat(resumenes("6 cuotas sin int.")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null");
        assertThat(resumenes("6 ctas sin interés")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null");
    }

    @Test
    void cuotasFueraDeRango2a36SeIgnoran() {
        assertThat(parse("1 cuota sin interés")).isEmpty();
        assertThat(parse("0 cuotas sin interés")).isEmpty();
        assertThat(parse("48 cuotas sin interés")).isEmpty();
        assertThat(resumenes("36 cuotas sin interés")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|36|null");
        assertThat(resumenes("2 cuotas sin interés")).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|2|null");
    }

    @Test
    void ignoraPreciosEnvioCuponesYPorcentajesSinMedio() {
        assertThat(parse("Envío gratis en compras mayores a $50.000")).isEmpty();
        assertThat(parse("21% OFF")).isEmpty();
        assertThat(parse("2x1 en remeras")).isEmpty();
        assertThat(parse("Usá el cupón BIENVENIDA y obtené 10% off")).isEmpty();
        assertThat(parse("10% OFF en tu primera compra con transferencia")).isEmpty();
        assertThat(parse("$12.500 con transferencia")).isEmpty();
    }

    @Test
    void porcentajesImposiblesSeIgnoranSinRomper() {
        assertThat(parse("150% off con transferencia")).isEmpty();
        assertThat(parse("0% off con transferencia")).isEmpty();
    }

    @Test
    void vacioONuloDaListaVacia() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("")).isEmpty();
        assertThat(parse("   \n ")).isEmpty();
    }

    @Test
    void esInsensibleAMayusculas() {
        assertThat(resumenes("10% descuento con TRANSFERENCIA")).containsExactly("Transferencia|DESCUENTO|null|10.00");
        assertThat(resumenes("3 Cuotas Sin Interes con VISA")).containsExactly("Visa|CUOTAS_SIN_INTERES|3|null");
    }

    @Test
    void sellaElAlcanceYElTextoFuente() {
        var p = PromoTextParser.parsear("Hola. 10% off con transferencia. Gracias", Alcance.PRODUCTO, REF).get(0);
        assertThat(p.alcance()).isEqualTo(Alcance.PRODUCTO);
        assertThat(p.textoFuente()).isEqualTo("10% off con transferencia");
        assertThat(p.tipoMedio()).isEqualTo(TipoMedio.MEDIO);
    }

    @Test
    void vigenciaHastaConAnioInferidoDeLaReferencia() {
        var p = parse("10% off con transferencia. Válido hasta el 30/11").get(0);
        assertThat(p.vigenciaDesde()).isNull();
        assertThat(p.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void vigenciaConAnioExplicito() {
        var p = parse("3 cuotas sin interés hasta el 30/11/2027").get(0);
        assertThat(p.vigenciaHasta()).isEqualTo(LocalDate.of(2027, 11, 30));
        assertThat(parse("3 cuotas sin interés hasta el 30/11/27").get(0).vigenciaHasta())
                .isEqualTo(LocalDate.of(2027, 11, 30));
    }

    @Test
    void vigenciaDelAl() {
        var p = parse("15% off con transferencia del 10/11 al 16/11").get(0);
        assertThat(p.vigenciaDesde()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(p.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void vigenciaQueCruzaElAnioUsaElSiguienteParaElFin() {
        var p = parse("15% off con transferencia del 20/12 al 05/01").get(0);
        assertThat(p.vigenciaDesde()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(p.vigenciaHasta()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void vigenciaConNombreDeMes() {
        assertThat(parse("10% off con transferencia hasta el 30 de noviembre").get(0).vigenciaHasta())
                .isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void unaFechaPasadaNoSeAdelantaAlAnioSiguiente() {
        assertThat(parse("10% off con transferencia hasta el 05/01").get(0).vigenciaHasta())
                .isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void fechasInvalidasSeIgnoranYHastaNCuotasNoEsUnaFecha() {
        var p = parse("10% off con transferencia hasta el 31/02").get(0);
        assertThat(p.vigenciaHasta()).isNull();
        var q = parse("Hasta 12 cuotas sin interés").get(0);
        assertThat(q.vigenciaHasta()).isNull();
        assertThat(q.cuotas()).isEqualTo(12);
    }

    @Test
    void laMismaPromoRepetidaSeColapsa() {
        assertThat(parse("3 cuotas sin interés. 3 CUOTAS SIN INTERES")).hasSize(1);
    }
}
