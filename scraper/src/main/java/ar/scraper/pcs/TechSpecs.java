package ar.scraper.pcs;

import java.util.List;
import lombok.Builder;

/**
 * Mirrors {@code Product.VisualAttrs}'s abstention policy: every field is fill-only,
 * {@code ""}/{@code 0}/{@code Gama.DESCONOCIDA}/{@code Certificacion.NINGUNA}/
 * {@code TipoAlmacenamiento.DESCONOCIDO}/{@code List.of()} means "the parser didn't assert this",
 * never "no socket"/"no watts".
 */
@Builder(toBuilder = true)
public record TechSpecs(
        String socket,
        String ddr,
        String formFactor,
        int watts,
        int capacidadGb,
        String tipoMemoria,
        Gama gama,
        Certificacion certificacion,
        int velocidadMhz,    // RAM only; 0 = abstención
        TipoAlmacenamiento tipoAlmacenamiento, // Almacenamiento only
        List<String> socketsSoportados, // Cooler only; empty = abstención
        String marcaChip,
        int generacion,
        int tierChipset,
        int modulos,      // RAM only; 0 = abstención
        boolean wifi,
        TipoCooler tipoCooler, // Cooler only; DESCONOCIDO = abstención
        int nivel,        // CPU/GPU only; 0 = abstención. El escalón DENTRO de la gama, y la ÚNICA
        TamanioGabinete tamanioGabinete, // Gabinete only; DESCONOCIDO = abstención. Eje DISTINTO de
        int radiadorMm,   // Cooler only; 0 = abstención. Sólo un token entero dígitos+mm cuenta.
        ClaseDisipador claseDisipador,
        int heatpipes
) {
    public static final TechSpecs EMPTY = builder().build();

    public static TechSpecsBuilder builder() {
        return new TechSpecsBuilder()
                .socket("").ddr("").formFactor("").tipoMemoria("").marcaChip("")
                .gama(Gama.DESCONOCIDA).certificacion(Certificacion.NINGUNA)
                .tipoAlmacenamiento(TipoAlmacenamiento.DESCONOCIDO).socketsSoportados(List.of())
                .tipoCooler(TipoCooler.DESCONOCIDO).tamanioGabinete(TamanioGabinete.DESCONOCIDO)
                .claseDisipador(ClaseDisipador.DESCONOCIDA);
    }
}
