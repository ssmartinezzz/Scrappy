package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public final class MlDtos {

    private MlDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Started {
        private String status;
        private String mensaje;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Estado {
        private boolean hasTextModel;
        private boolean hasImageModel;
        private JsonNode textMeta;
        private Training training;
        private long embeddingsCount;
        private int totalProductos;
        private double coveragePct;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Training {
        private boolean running;
        private String phase;
        private int pct;
        private String msg;
        private String startedAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Resultado {
        private boolean running;
        private String phase;
        private int pct;
        private String msg;
        private boolean done;
    }
}
