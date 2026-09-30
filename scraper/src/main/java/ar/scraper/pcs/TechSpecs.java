package ar.scraper.pcs;

import java.util.List;

/**
 * Mirrors {@code Product.VisualAttrs}'s abstention policy: every field is fill-only,
 * {@code ""}/{@code 0}/{@code Gama.DESCONOCIDA}/{@code Certificacion.NINGUNA}/
 * {@code TipoAlmacenamiento.DESCONOCIDO}/{@code List.of()} means "the parser didn't assert this",
 * never "no socket"/"no watts".
 */
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
    public static final TechSpecs EMPTY =
            new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                    0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, TipoCooler.DESCONOCIDO, 0,
                    TamanioGabinete.DESCONOCIDO, 0, ClaseDisipador.DESCONOCIDA, 0);

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, Gama.DESCONOCIDA, Certificacion.NINGUNA);
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                0, TipoAlmacenamiento.DESCONOCIDO);
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, List.of());
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, "", 0, 0, 0, false);
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, TipoCooler.DESCONOCIDO);
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, tipoCooler, 0);
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler, int nivel) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, tipoCooler, nivel, TamanioGabinete.DESCONOCIDO, 0);
    }

    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler, int nivel, TamanioGabinete tamanioGabinete, int radiadorMm) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, tipoCooler, nivel, tamanioGabinete, radiadorMm, ClaseDisipador.DESCONOCIDA, 0);
    }
}
