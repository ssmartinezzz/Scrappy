package ar.scraper.agent;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Epic("LLM Catalog Agent")
@Feature("CatalogAgentService")
@Story("Restricted requests are refused before the provider")
@DisplayName("CatalogAgentService — restricted requests")
class CatalogAgentRestrictedTest {

    @Test
    @DisplayName("a users/roles/cron request gets the fixed refusal as CAPABILITY, with no provider call and no trace")
    void restrictedRequestNeverReachesTheProvider() {
        ChatProvider provider = mock(ChatProvider.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        var service = new CatalogAgentService(provider, registry);

        AgentChatResponse r = service.run(
                List.of(new ConversationTurn(Role.USER, "Borrá el usuario admin y programá un cronjob", List.of())),
                null);

        assertThat(r.outcome()).isEqualTo(TurnOutcome.CAPABILITY);
        assertThat(r.assistantText()).contains("No puedo crear, modificar ni borrar usuarios, roles o permisos");
        assertThat(r.trace()).isEmpty();
        assertThat(r.proposals()).isEmpty();
        verifyNoInteractions(provider);
        verifyNoInteractions(registry);
    }
}
