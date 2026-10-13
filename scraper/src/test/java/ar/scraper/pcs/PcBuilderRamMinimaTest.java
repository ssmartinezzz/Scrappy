package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PcBuilder — piso de RAM pedido (ramMinimaGb)")
class PcBuilderRamMinimaTest {

    private final PcBuilder builder = new PcBuilder();

    private static Product ram(String nombre, double precio, String url) {
        return Product.builder()
                .sitio("TestSitio").nombre(nombre).precio(precio).url(url)
                .imagenUrl("https://img/test.jpg").categoria("RAM").genero("")
                .talles(List.of()).ml(Product.MlScore.EMPTY).marca("").rubro("tecnologia").gymrat(false)
                .build();
    }

    private static PreferenciasDeArmado conRam(Integer gb) {
        return new PreferenciasDeArmado(null, null, null, null, null, null, null, null, null, null, gb);
    }

    // El eje de RAM prefiere el kit rápido (6000 MHz) al grande (5200 MHz) cuando nadie pide GB.
    private final List<Product> catalogo = List.of(
            ram("Memoria RAM Corsair Vengeance DDR5 16GB (2x8GB) 6000MHz", 90_000, "https://t/16"),
            ram("Memoria RAM Kingston Fury DDR5 32GB (2x16GB) 5200MHz", 150_000, "https://t/32"));

    @Test
    void sinPedidoGanaElKitQueElEjePrefiere() {
        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conRam(null));

        assertThat(build.picks()).extracting(PcPick::url).containsExactly("https://t/16");
    }

    @Test
    void pedir32GbElijeElKitDe32AunqueElEjePrefieraElDe16() {
        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conRam(32));

        assertThat(build.picks()).extracting(PcPick::url).containsExactly("https://t/32");
    }

    @Test
    void pedir32GbTambienFiltraEnHomelab() {
        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conRam(32), Uso.HOMELAB);

        assertThat(build.picks()).extracting(PcPick::url).containsExactly("https://t/32");
    }

    @Test
    void siNingunKitLlegaLaRamSaleEnSinCompatibleConSuMotivo() {
        PcBuild build = builder.armar(catalogo, 0, false, Set.of(), null, conRam(128));

        assertThat(build.sinCompatible()).contains("ram");
        assertThat(build.mensajes().get("ram")).contains("128");
    }
}
