package ar.scraper.promos;

import ar.scraper.model.promo.TipoMedio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MediosDePago — one owner for alias -> canonical medio de pago")
class MediosDePagoTest {

    private static MedioPago medio(String nombre, TipoMedio tipo) {
        return new MedioPago(nombre, tipo);
    }

    /** Reads the seed out of V101 so the migration and the alias table cannot drift. */
    private static List<MedioPago> semillaDeV101() throws IOException {
        Path f = Path.of("src/main/resources/db/migration/V101__promos_financiacion.sql");
        String sql = Files.readString(f);
        int ini = sql.indexOf("INSERT INTO medio_pago");
        String bloque = sql.substring(ini, sql.indexOf(';', ini));
        Matcher m = Pattern.compile("\\('([^']+)','([a-z]+)'\\)").matcher(bloque);
        List<MedioPago> out = new ArrayList<>();
        while (m.find()) {
            out.add(medio(m.group(1), TipoMedio.deSql(m.group(2)).orElseThrow()));
        }
        return out;
    }

    @Test
    void todoNombreDeLaSemillaResuelveASiMismo() throws IOException {
        List<MedioPago> semilla = semillaDeV101();
        assertThat(semilla).hasSizeGreaterThan(30);
        for (MedioPago m : semilla) {
            assertThat(MediosDePago.resolver(m.nombre())).as(m.nombre()).contains(m);
        }
    }

    @Test
    @DisplayName("every card network Java can emit as a promo's red is seeded as tarjeta (else sp_upsert_promos aborts the run)")
    void todaRedDeJavaEstaSembradaComoTarjeta() throws IOException {
        String fuente = Files.readString(Path.of("src/main/java/ar/scraper/promos/MediosDePago.java"));
        Matcher m = Pattern.compile("e\\(\"([^\"]+)\",\\s*TipoMedio\\.TARJETA").matcher(fuente);
        List<MedioPago> redesDeJava = new ArrayList<>();
        while (m.find()) redesDeJava.add(medio(m.group(1), TipoMedio.TARJETA));

        assertThat(redesDeJava).isNotEmpty();
        assertThat(semillaDeV101()).containsAll(redesDeJava);
    }

    @Test
    void esInsensibleAMayusculasYAcentos() {
        assertThat(MediosDePago.resolver("NACION")).contains(medio("Nación", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("  mercado credito ")).contains(medio("Mercado Crédito", TipoMedio.PASARELA));
        assertThat(MediosDePago.resolver("UALA")).contains(medio("Ualá", TipoMedio.BILLETERA));
    }

    @Test
    void nombresDePaymentSystemDeVtex() {
        assertThat(MediosDePago.resolver("Visa Electron")).contains(medio("Visa", TipoMedio.TARJETA));
        assertThat(MediosDePago.resolver("Mastercard Debit")).contains(medio("Mastercard", TipoMedio.TARJETA));
        assertThat(MediosDePago.resolver("American Express")).contains(medio("American Express", TipoMedio.TARJETA));
        assertThat(MediosDePago.resolver("Maestro")).contains(medio("Maestro", TipoMedio.TARJETA));
        assertThat(MediosDePago.resolver("MercadoPagoPro")).contains(medio("Mercado Pago", TipoMedio.BILLETERA));
        assertThat(MediosDePago.resolver("MercadoPagoOff")).contains(medio("Mercado Pago", TipoMedio.BILLETERA));
        assertThat(MediosDePago.resolver("Mercado Credito")).contains(medio("Mercado Crédito", TipoMedio.PASARELA));
        assertThat(MediosDePago.resolver("Modo Payment")).contains(medio("Modo", TipoMedio.BILLETERA));
        assertThat(MediosDePago.resolver("GOcuotas")).contains(medio("GOcuotas", TipoMedio.PASARELA));
    }

    @Test
    void clavesDeGatewayDeTiendanube() {
        assertThat(MediosDePago.resolver("pago-nube")).contains(medio("Pago Nube", TipoMedio.PASARELA));
        assertThat(MediosDePago.resolver("Pago Nube")).contains(medio("Pago Nube", TipoMedio.PASARELA));
        assertThat(MediosDePago.resolver("mercado-pago")).contains(medio("Mercado Pago", TipoMedio.BILLETERA));
        assertThat(MediosDePago.resolver("wire_transfer")).contains(medio("Transferencia", TipoMedio.MEDIO));
        assertThat(MediosDePago.resolver("credit_card")).contains(medio("Tarjeta de crédito", TipoMedio.MEDIO));
        assertThat(MediosDePago.resolver("debit_card")).contains(medio("Tarjeta de débito", TipoMedio.MEDIO));
    }

    @Test
    void nombresDeBancosEnBanners() {
        assertThat(MediosDePago.resolver("Banco Santander")).contains(medio("Santander", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("Santander Río")).contains(medio("Santander", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("BBVA Francés")).contains(medio("BBVA", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("Banco Nación")).contains(medio("Nación", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("BNA")).contains(medio("Nación", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("Banco Provincia")).contains(medio("Provincia", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("Galicia")).contains(medio("Galicia", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("Naranja X")).contains(medio("Naranja X", TipoMedio.BANCO));
        assertThat(MediosDePago.resolver("Naranja")).contains(medio("Naranja", TipoMedio.TARJETA));
        assertThat(MediosDePago.resolver("Cuenta DNI")).contains(medio("Cuenta DNI", TipoMedio.BILLETERA));
        assertThat(MediosDePago.resolver("Personal Pay")).contains(medio("Personal Pay", TipoMedio.BILLETERA));
    }

    @Test
    void desconocidoOVacioSeAbstiene() {
        assertThat(MediosDePago.resolver("Cencosud Pay")).isEmpty();
        assertThat(MediosDePago.resolver("Vale")).isEmpty();
        assertThat(MediosDePago.resolver("Banco Inventado")).isEmpty();
        assertThat(MediosDePago.resolver("")).isEmpty();
        assertThat(MediosDePago.resolver("   ")).isEmpty();
        assertThat(MediosDePago.resolver(null)).isEmpty();
    }

    @Test
    void encontrarEnTextoLibreRespetaFronterasYElMasLargoGana() {
        assertThat(MediosDePago.encontrarEn("3 y 6 cuotas con Visa y Mastercard"))
                .containsExactly(medio("Visa", TipoMedio.TARJETA), medio("Mastercard", TipoMedio.TARJETA));
        assertThat(MediosDePago.encontrarEn("con tarjetas Banco Galicia"))
                .containsExactly(medio("Galicia", TipoMedio.BANCO));
        assertThat(MediosDePago.encontrarEn("pagando con Naranja X"))
                .containsExactly(medio("Naranja X", TipoMedio.BANCO));
        assertThat(MediosDePago.encontrarEn("visas y devisado")).containsExactly();
        assertThat(MediosDePago.encontrarEn("visa visa")).containsExactly(medio("Visa", TipoMedio.TARJETA));
        assertThat(MediosDePago.encontrarEn(null)).isEmpty();
    }

    @Test
    void palabrasComunesNoSonBancosEnTextoLibre() {
        assertThat(MediosDePago.encontrarEn("envio a toda la nacion, en la ciudad y la provincia")).isEmpty();
        assertThat(MediosDePago.encontrarEn("con Banco Ciudad")).containsExactly(medio("Ciudad", TipoMedio.BANCO));
    }
}
