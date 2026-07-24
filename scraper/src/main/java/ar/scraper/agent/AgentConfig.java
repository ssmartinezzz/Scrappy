package ar.scraper.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code LLM_*} env vars (llm-catalog-nlp, design D6) — all
 * optional, resolved with safe local defaults in
 * {@code application.properties} ({@code openai_compat} /
 * {@code http://localhost:11434/v1} / {@code qwen3:14b} / no key). NOT
 * registered in {@link ar.scraper.config.RequiredEnvVarsGuard#REQUIRED_VARS}
 * — see {@code RequiredEnvVarsGuardLlmTest}.
 */
@Component
public class AgentConfig {

    @Value("${llm.provider}")
    private String provider;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.api-key}")
    private String apiKey;

    public String provider() { return provider; }
    public String baseUrl()  { return baseUrl; }
    public String model()    { return model; }
    public String apiKey()   { return apiKey; }
    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }
}
