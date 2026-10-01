package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** Payloads of the run-control and site/config endpoints. */
public final class ScrapeDtos {

    private ScrapeDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Status {
        private String status;
        private String mensaje;
        private boolean tieneData;
        private Integer total;
        private Integer mlRefinadas;
        private Boolean mlModeloActivo;
        private Map<String, ExtractionStats> extractionStats;
        private RunInfo run;
        private Progreso progreso;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ExtractionStats {
        private int total;
        private int valid;
        private int misses;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class RunInfo {
        private String uuid;
        private String startedAt;
        private boolean cancelando;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Progreso {
        private int total;
        private int completados;
        private int productos;
        private List<SitioProgreso> sitios;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SitioProgreso {
        private String nombre;
        private String estado;
        private int count;
        private long durMs;
        private String error;

        /** The shape {@code /api/status} and the event stream both serve: lower-case state, error cut at 60. */
        public static SitioProgreso desde(String nombre, String estado, int productos, long durMs, String error) {
            String corto = error != null && !error.isBlank()
                    ? (error.length() > 60 ? error.substring(0, 60) + "..." : error)
                    : null;
            return new SitioProgreso(nombre, estado.toLowerCase(), productos, durMs, corto);
        }
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Interrumpida {
        private boolean hayInterrumpida;
        private String uuid;
        private String startedAt;
        private Boolean soloFaltaLaPasadaFinal;
        private List<String> atendidos;
        private List<String> pendientes;
        private List<String> salteados;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Retomar {
        private boolean retomando;
        private String mensaje;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Descartar {
        private int descartadas;
        private String mensaje;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Cancelar {
        private boolean cancelando;
        private String mensaje;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Iniciar {
        private boolean iniciado;
        private String mensaje;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Sitios {
        private List<SitioBase> base;
        private List<SitioExtra> extras;
        private double precioMinimo;
        private double precioMaximo;
        private String moneda;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SitioBase {
        private String nombre;
        private String url;
        private String tipo;
        private String rubro;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SitioExtra {
        private String nombre;
        private String url;
        private String plataforma;
        private String tipo;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfigResult {
        private Double precioMinimo;
        private Double precioMaximo;
        private boolean ok;
    }
}
