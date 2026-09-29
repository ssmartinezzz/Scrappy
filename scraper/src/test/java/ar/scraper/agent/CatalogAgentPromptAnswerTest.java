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
@Story("System prompt — answering from the returned rows")
@DisplayName("CatalogAgentService prompt — answering from rows")
class CatalogAgentPromptAnswerTest {

    private String prompt() {
        return new CatalogAgentService(mock(ChatProvider.class), mock(ToolRegistry.class)).systemPrompt();
    }

    @Test
    @DisplayName("prompt: after a search, list the returned rows with name, site, price and discount")
    void answerListsTheReturnedRows() {
        assertThat(prompt())
                .contains("listando los productos devueltos")
                .contains("nombre, sitio y precio")
                .contains("descuentoPct");
    }

    @Test
    @DisplayName("prompt: no unrequested advice such as compatibility or buying tips")
    void noUnrequestedAdvice() {
        assertThat(prompt())
                .contains("consejos no pedidos")
                .contains("compatibilidad");
    }

    @Test
    @DisplayName("prompt: a review states how many were reviewed and says explicitly when none needs a change")
    void reviewStatesCountAndNoChange() {
        assertThat(prompt())
                .contains("cuántos productos revisaste")
                .contains("ninguno necesita cambios");
    }

    @Test
    @DisplayName("prompt: categoria matches the family, not exact equality")
    void categoriaIsFamily() {
        assertThat(prompt()).contains("familia");
    }
}
