package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.TipoMedio;
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

    // Real strings captured 2026-11-13 from sporting.com.ar/medios-de-pagos and batuk.com.ar / dcshoes.com.ar.

    @Test
    void modoSoloCuentaCuandoElTextoLoNombraComoBilletera() {
        assertThat(resumenes("30% de reintegro (tope $15.000 por compra) con MODO, los jueves."))
                .containsExactly("Modo|DESCUENTO|null|30.00");
        assertThat(resumenes("20% de reintegro (tope $10.000 por compra) con MODO (Visa, Mastercard y American Express), los martes, jueves y domingos."))
                .containsExactlyInAnyOrder("Modo|DESCUENTO|null|20.00", "Modo|DESCUENTO|null|20.00",
                        "Modo|DESCUENTO|null|20.00");
    }

    @Test
    void modoComoSustantivoNoEsLaBilletera() {
        assertThat(resumenes("Elegí el modo de pago al final: 10% de descuento con transferencia"))
                .containsExactly("Transferencia|DESCUENTO|null|10.00");
        assertThat(resumenes("De este modo ahorrás: 3 cuotas sin interés"))
                .containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null");
        assertThat(resumenes("20% de descuento con modo de pago")).isEmpty();
    }

    @Test
    void elCuponDeUnaBarraNoTapaElDescuentoQueLeSigue() {
        assertThat(resumenes("HASTA 3 CUOTAS SIN INTERÉS. 10% OFF EN TU PRIMERA COMPRA CON EL CODIGO \"BATUK10\" 10% DESCUENTO CON TRANSFERENCIA"))
                .containsExactlyInAnyOrder("Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null",
                        "Transferencia|DESCUENTO|null|10.00");
    }

    @Test
    void listaDeCuotasConHastaEnElUltimoValor() {
        assertThat(resumenes("3, 6, 9 Y HASTA 12 CUOTAS SIN INTERÉS!"))
                .containsExactlyInAnyOrder("Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null",
                        "Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null",
                        "Tarjeta de crédito|CUOTAS_SIN_INTERES|9|null",
                        "Tarjeta de crédito|CUOTAS_SIN_INTERES|12|null");
    }

    private static List<String> conRed(String texto) {
        return parse(texto).stream()
                .map(p -> p.medioPago() + "|" + p.redMedioPago() + "|" + p.tipo() + "|" + p.cuotas() + "|" + p.pct())
                .toList();
    }

    @Test
    void unBancoConSuTarjetaEsUnaSolaPromoDeEsaRed() {
        assertThat(conRed("12 cuotas sin interés con Santander (Visa)."))
                .containsExactly("Santander|Visa|CUOTAS_SIN_INTERES|12|null");
    }

    @Test
    void sinBancoLaRedEsElMedio() {
        assertThat(resumenes("Hasta 12 Cuotas sin interés Visa Y Mastercard"))
                .containsExactly("Visa|CUOTAS_SIN_INTERES|12|null", "Mastercard|CUOTAS_SIN_INTERES|12|null");
        assertThat(conRed("Hasta 12 Cuotas sin interés Visa Y Mastercard"))
                .containsExactly("Visa|null|CUOTAS_SIN_INTERES|12|null", "Mastercard|null|CUOTAS_SIN_INTERES|12|null");
    }

    @Test
    void unBancoSinRedesNoExigeNinguna() {
        assertThat(conRed("6 cuotas sin interés con Galicia"))
                .containsExactly("Galicia|null|CUOTAS_SIN_INTERES|6|null");
    }

    @Test
    void lineaRealDeSportingNoDejaUnaPromoDeAmexSuelta() {
        // Captured 2026-11-13 from sporting.com.ar/medios-de-pagos: a Macro-only promo.
        String linea = "12 cuotas sin interés con Macro (Visa, Mastercard y American Express), los miércoles y sábados.";
        assertThat(conRed(linea)).containsExactlyInAnyOrder(
                "Macro|Visa|CUOTAS_SIN_INTERES|12|null",
                "Macro|Mastercard|CUOTAS_SIN_INTERES|12|null",
                "Macro|American Express|CUOTAS_SIN_INTERES|12|null");
    }

    @Test
    void unDescuentoDeBilleteraConRedesTambienSeAtaALaRed() {
        assertThat(conRed("20% de reintegro (tope $10.000 por compra) con MODO (Visa, Mastercard y American Express), los martes."))
                .containsExactlyInAnyOrder(
                        "Modo|Visa|DESCUENTO|null|20.00",
                        "Modo|Mastercard|DESCUENTO|null|20.00",
                        "Modo|American Express|DESCUENTO|null|20.00");
    }

    @Test
    void unaRedDesconocidaEntreParentesisSeOmite() {
        assertThat(conRed("12 cuotas sin interés con Macro (Visa y Diners)"))
                .containsExactly("Macro|Visa|CUOTAS_SIN_INTERES|12|null");
    }

    @Test
    void unBancoDesconocidoConRedesConocidasNoSeArchivaComoPromoDeLaTarjeta() {
        // The bank is not in the seed: filing the line card-wide would claim every Mastercard qualifies.
        assertThat(conRed("12 cuotas sin interés con Banco del Sol (Mastercard)")).isEmpty();
        assertThat(conRed("10% de descuento con Banco del Sol (Visa y Mastercard)")).isEmpty();
    }

    @Test
    void elBancoDesconocidoNoTapaAlConocidoDeLaMismaLinea() {
        assertThat(conRed("6 cuotas sin interés con Galicia y con Banco del Sol (Mastercard)"))
                .containsExactly("Galicia|null|CUOTAS_SIN_INTERES|6|null");
    }

    @Test
    void unaListaDeRedesSinDuenoSigueSiendoDeLaTarjeta() {
        assertThat(conRed("12 cuotas sin interés (Visa y Mastercard)"))
                .containsExactly("Visa|null|CUOTAS_SIN_INTERES|12|null", "Mastercard|null|CUOTAS_SIN_INTERES|12|null");
    }
}
