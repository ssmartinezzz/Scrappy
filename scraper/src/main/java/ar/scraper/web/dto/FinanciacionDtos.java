package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Payloads of the financing presets, buy recommendation and macro indices endpoints. */
public final class FinanciacionDtos {

    private FinanciacionDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Presets {
        private List<Preset> presets;
        private Preset activo;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Preset {
        private int id;
        private String label;
        private double recargoPct;
        private int cuotas;
        private boolean activo;
    }

    /** With no price history only {@code senal} and {@code mensaje} are present. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Recomendacion {
        private String senal;
        private String mensaje;
        private String emoji;
        private Integer scoreCompra;
        private Double cambioReal;
        private Integer pctDelMin;
        private Double precioMin;
        private Double precioMax;
        private String tendencia;
        private String indice;
        private String confianza;
        private Integer diasExtrapolados;
        private Double inflacionMensual;
        private Double inflacionInteranual;
        private Integer puntosHistorial;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Indices {
        private Resumen ipc;
        private Resumen usd;
        private String actualizado;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Resumen {
        private String indice;
        private double ultimoValor;
        private String ultimaFecha;
        private Double variacionMensual;
        private Double variacionInteranual;
        private Double variacion3m;
        private String confianza;
        private List<Punto> ultimos;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Punto {
        private String fecha;
        private double valor;
    }
}
