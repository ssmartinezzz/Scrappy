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
        int heatpipes,
        String modelo,    // CPU/GPU only; "" = abstención. Clave canónica del modelo con sufijo (rtx-5070-ti)
        int rendimiento,  // CPU/GPU only; 0 = abstención. Puntaje de TablaDeRendimiento para `modelo`
        int latenciaCl,   // RAM only; 0 = abstención. CAS latency en ciclos (CL30)
        int anioChipset   // Motherboard only; 0 = abstención. Año de lanzamiento del chipset
) {
    public static final TechSpecs EMPTY = builder().build();

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler, int nivel, TamanioGabinete tamanioGabinete, int radiadorMm,
            ClaseDisipador claseDisipador, int heatpipes) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion, velocidadMhz,
                tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos, wifi, tipoCooler,
                nivel, tamanioGabinete, radiadorMm, claseDisipador, heatpipes, "", 0, 0, 0);
    }

    public static TechSpecsBuilder builder() {
        return new TechSpecsBuilder()
                .socket("").ddr("").formFactor("").tipoMemoria("").marcaChip("")
                .gama(Gama.DESCONOCIDA).certificacion(Certificacion.NINGUNA)
                .tipoAlmacenamiento(TipoAlmacenamiento.DESCONOCIDO).socketsSoportados(List.of())
                .tipoCooler(TipoCooler.DESCONOCIDO).tamanioGabinete(TamanioGabinete.DESCONOCIDO)
                .claseDisipador(ClaseDisipador.DESCONOCIDA).modelo("");
    }
}
