package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locking test for llm-catalog-nlp design D6: {@code LLM_PROVIDER}/{@code
 * LLM_MODEL}/{@code LLM_BASE_URL}/{@code LLM_API_KEY} must NEVER be added to
 * {@link RequiredEnvVarsGuard#REQUIRED_VARS} — every one of them has a safe
 * local default (local Ollama needs no key), so the fail-fast guard must
 * stay scoped to DB/CORS only. A future edit that adds an {@code LLM_*} var
 * here breaks this test on purpose.
 */
@Epic("Configuration")
@Feature("Environment-only config fail-fast")
@Story("LLM agent config stays optional (D6)")
@DisplayName("RequiredEnvVarsGuard — LLM_* vars are never required")
class RequiredEnvVarsGuardLlmTest {

    @Test
    @DisplayName("REQUIRED_VARS contains no LLM_* entry")
    void requiredVarsExcludesAnyLlmVar() {
        assertThat(RequiredEnvVarsGuard.REQUIRED_VARS)
                .noneMatch(v -> v.startsWith("LLM_"));
    }
}
