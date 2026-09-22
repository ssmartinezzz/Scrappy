package ar.scraper.pcs.specs;

import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GabineteSpecsReader} — el form factor es de la fase 1; el tamaño de
 * torre es de la fase 9 (D1: son dos ejes distintos, no uno).
 *
 * <p>Todos los nombres de abajo son filas reales de la dev DB (2026-09-22).</p>
 */
@DisplayName("GabineteSpecsReader — form factor (fase 1) + tamaño de torre (fase 9)")
class GabineteSpecsReaderTest {

    private final GabineteSpecsReader reader = new GabineteSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsGabinete() {
        assertThat(reader.categoria()).isEqualTo("Gabinete");
    }

    // ── form factor: sin cambios respecto de la fase 1 ───────────────────

    @Test
    void leeFormFactorEatx() {
        assertThat(leer("OUTLET - Gabinete Xigmatek Pano II Black Full Tower E-ATX - Fan cooler x8").formFactor())
                .isEqualTo("EATX");
    }

    @Test
    void leeFormFactorMatx() {
        assertThat(leer("GABINETE GAMER GIGABYTE C102G M-ATX BLACK").formFactor()).isEqualTo("MATX");
    }

    @Test
    void abstieneElFormFactorCuandoElNombreNoLoDice() {
        assertThat(leer("Gabinete Thermaltake Versa H16 TG Argb Snow White").formFactor()).isEmpty();
    }

    // ── tamaño de torre: fase 9 ──────────────────────────────────────────

    @Test
    void leeMidTower() {
        assertThat(leer("GABINETE ASUS ROG STRIX HELIOS GX601 RGB MID-TOWER EATX NEGRO").tamanioGabinete())
                .isEqualTo(TamanioGabinete.MID);
    }

    @Test
    void leeFullTowerConYSinGuion() {
        assertThat(leer("GABINETE CORSAIR ICUE 7000X RGB TG FULL-TOWER ATX WHITE").tamanioGabinete())
                .isEqualTo(TamanioGabinete.FULL);
        assertThat(leer("OUTLET - Gabinete Xigmatek Pano II Black Full Tower E-ATX").tamanioGabinete())
                .isEqualTo(TamanioGabinete.FULL);
    }

    @Test
    void leeMiniTower() {
        assertThat(leer("Gabinete Corsair Frame 2800x Rs-r Argb Mini-tower Tg Black").tamanioGabinete())
                .isEqualTo(TamanioGabinete.MINI);
    }

    @Test
    void unFullQueNoEsDeTorreNoEsUnFullTower() {
        // "Full Modular" es la fuente, no el gabinete — el par de tokens
        // adyacentes es la única lectura segura, igual que el "m"+"2" de
        // AlmacenamientoSpecsReader. Acá el nombre SÍ dice mid-tower.
        assertThat(leer("Gabinete Corsair Frame 4000d Mid-tower Tg Full Modular White").tamanioGabinete())
                .isEqualTo(TamanioGabinete.MID);
        // Y acá "Full" está solo: abstención, no FULL.
        assertThat(leer("Gabinete Gamemax Infinity Plus Full TG Vidrio Templado").tamanioGabinete())
                .isEqualTo(TamanioGabinete.DESCONOCIDO);
    }

    @Test
    void abstieneElTamanioCuandoElNombreNoLoDice() {
        // 576 de 622 filas del catálogo caen acá (medido 2026-09-22).
        assertThat(leer("Gabinete Adata XPG Invader X BTF Black").tamanioGabinete())
                .isEqualTo(TamanioGabinete.DESCONOCIDO);
    }

    @Test
    void elFormFactorYElTamanioSonIndependientes() {
        // D1: un mismo nombre puede declarar los dos, uno, o ninguno.
        TechSpecs t = leer("GABINETE CORSAIR ICUE 7000X RGB TG FULL-TOWER ATX WHITE");
        assertThat(t.formFactor()).isEqualTo("ATX");
        assertThat(t.tamanioGabinete()).isEqualTo(TamanioGabinete.FULL);
    }
}
