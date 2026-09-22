package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PcBuilder} con las cuatro preferencias de la fase 9 cableadas
 * (T4): el slot cooler que se abre por pedido (D4) y el piso de watts que
 * sube pero nunca baja (D5) son las dos que cambian el ARMADO, no sólo el
 * pool de un slot.
 */
@DisplayName("PcBuilder — capacidad, tamaño de gabinete, cooler y watts pedidos (fase 9)")
class PcBuilderFase9Test {

    private final PcBuilder builder = new PcBuilder();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    private static PreferenciasDeArmado soloCapacidad(Integer gb) {
        return new PreferenciasDeArmado(null, null, null, null, null, null, gb, null, null, null);
    }

    private static PreferenciasDeArmado soloTamanio(TamanioGabinete t) {
        return new PreferenciasDeArmado(null, null, null, null, null, null, null, t, null, null);
    }

    private static PreferenciasDeArmado soloCooler(TipoCooler t) {
        return new PreferenciasDeArmado(null, null, null, null, null, null, null, null, t, null);
    }

    private static PreferenciasDeArmado soloWatts(Integer w) {
        return new PreferenciasDeArmado(null, null, null, null, null, null, null, null, null, w);
    }

    // ── capacidad mínima ─────────────────────────────────────────────────

    @Test
    void laCapacidadPedidaVetaElDiscoQueNoLlega() {
        List<Product> catalogo = List.of(
                producto("Disco SSD M.2 NVMe Kingston NV3 500GB", 30_000, "Almacenamiento", "https://t/500"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloCapacidad(1024));

        assertThat(build.sinCompatible()).contains("almacenamiento");
        assertThat(build.mensajes().get("almacenamiento")).contains("1024");
    }

    @Test
    void conLaCapacidadPedidaElDiscoQueSiLlegaSeElige() {
        List<Product> catalogo = List.of(
                producto("Disco SSD M.2 NVMe Kingston NV3 500GB", 30_000, "Almacenamiento", "https://t/500"),
                producto("Disco SSD M.2 NVMe Kingston NV3 2TB", 180_000, "Almacenamiento", "https://t/2tb"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloCapacidad(1024));

        assertThat(build.picks()).extracting(PcPick::url).contains("https://t/2tb");
    }

    // ── tamaño de gabinete ───────────────────────────────────────────────

    @Test
    void elTamanioPedidoVetaAlGabineteQueNoLoDeclara() {
        // El caso frecuente: 576 de 622 gabinetes no dicen su tamaño (D2).
        List<Product> catalogo = List.of(
                producto("Gabinete Adata XPG Invader X BTF Black", 90_000, "Gabinete", "https://t/g"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloTamanio(TamanioGabinete.MID));

        assertThat(build.sinCompatible()).contains("gabinete");
        assertThat(build.mensajes().get("gabinete")).contains("MID");
    }

    @Test
    void elTamanioPedidoEligeAlQueSiLoDeclara() {
        List<Product> catalogo = List.of(
                producto("Gabinete Adata XPG Invader X BTF Black", 90_000, "Gabinete", "https://t/mudo"),
                producto("GABINETE CORSAIR ICUE 7000X RGB TG FULL-TOWER ATX WHITE", 400_000, "Gabinete",
                        "https://t/full"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloTamanio(TamanioGabinete.FULL));

        assertThat(build.picks()).extracting(PcPick::url).contains("https://t/full");
    }

    // ── cooler: D4, el slot se ABRE por pedido ───────────────────────────

    @Test
    void sinPedidoDeCoolerYSinGamaAltaNoHaySlotCooler() {
        // Byte por byte como la fase 8: el cooler es una decisión de tier.
        List<Product> catalogo = List.of(
                producto("CPU Water Cooler Lovingcool 360mm AK-B360-03", 120_000, "Cooler", "https://t/aio"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA);

        assertThat(build.picks()).extracting(PcPick::slot).doesNotContain("cooler");
        assertThat(build.sinStock()).doesNotContain("Cooler");
    }

    @Test
    void pedirUnTipoDeCoolerAbreElSlotAunqueLaGamaNoSeaAlta() {
        List<Product> catalogo = List.of(
                producto("CPU Water Cooler Lovingcool 360mm AK-B360-03", 120_000, "Cooler", "https://t/aio"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), Gama.MEDIA, soloCooler(TipoCooler.LIQUIDO));

        assertThat(build.picks()).extracting(PcPick::slot).contains("cooler");
        assertThat(build.picks()).extracting(PcPick::url).contains("https://t/aio");
    }

    @Test
    void pedirAireVetaLaLiquidaYDejaElMotivo() {
        List<Product> catalogo = List.of(
                producto("CPU Water Cooler Lovingcool 360mm AK-B360-03", 120_000, "Cooler", "https://t/aio"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloCooler(TipoCooler.AIRE));

        assertThat(build.sinCompatible()).contains("cooler");
        assertThat(build.mensajes().get("cooler")).contains("AIRE");
    }

    // ── watts: D5, el piso pedido SUBE pero nunca baja ───────────────────

    @Test
    void elPisoDeWattsPedidoVetaLaFuenteQueNoLoAlcanza() {
        List<Product> catalogo = List.of(
                producto("Fuente Corsair CX550 550W 80 Plus Bronze", 90_000, "Fuente", "https://t/550"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloWatts(850));

        assertThat(build.sinCompatible()).contains("fuente");
    }

    @Test
    void unPisoPedidoMasBajoQueElDeLaGamaNoBajaElDeLaGama() {
        // D5: gama ALTA con GPU pide 1000 W. Pedir 550 no puede dejar entrar
        // una de 650: el piso de seguridad es del armado, no del usuario.
        List<Product> catalogo = List.of(
                producto("Fuente Corsair RM650 650W 80 Plus Gold", 120_000, "Fuente", "https://t/650"));

        PcBuild build = builder.armar(catalogo, 0, true, Set.of(), Gama.ALTA, soloWatts(550));

        assertThat(build.sinCompatible()).contains("fuente");
    }

    @Test
    void unPisoPedidoMasAltoQueElDeLaGamaSiMandaSobreElDeLaGama() {
        // Sin gama el piso es 450; pedir 1200 tiene que vetar una de 750.
        List<Product> catalogo = List.of(
                producto("Fuente Corsair RM750 750W 80 Plus Gold", 130_000, "Fuente", "https://t/750"));

        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, soloWatts(1200));

        assertThat(build.sinCompatible()).contains("fuente");
    }
}
