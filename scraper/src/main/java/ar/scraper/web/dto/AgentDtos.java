package ar.scraper.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Payloads of the catalog agent endpoints (the chat reply itself is {@code AgentChatResponse}). */
public final class AgentDtos {

    private AgentDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Models {
        private List<String> available;
        @com.fasterxml.jackson.annotation.JsonProperty("default")
        private String defaultModel;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Applied {
        private boolean ok;
        private int applied;
        private String mensaje;
    }
}
