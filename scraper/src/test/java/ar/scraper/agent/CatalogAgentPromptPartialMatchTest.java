package ar.scraper.agent;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Epic("LLM Catalog Agent")
@Feature("CatalogAgentService")
@Story("System prompt — partial matches")
@DisplayName("CatalogAgentService prompt — partial matches")
class CatalogAgentPromptPartialMatchTest {

    @Test
    @DisplayName("prompt: partial rows are presented as 'no encontré exactamente X, lo más parecido es…', never as exact")
    void partialMatchesAreNeverPresentedAsExact() {
        String prompt = new CatalogAgentService(mock(ChatProvider.class), mock(ToolRegistry.class)).systemPrompt();

        assertThat(prompt).contains("coincidencia");
        assertThat(prompt).contains("parcial");
        assertThat(prompt).contains("terminosFaltantes");
        assertThat(prompt).contains("no encontré exactamente");
        assertThat(prompt).contains("lo más parecido es");
        assertThat(prompt).contains("relevancia");
    }
}
