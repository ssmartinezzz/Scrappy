package ar.scraper.agent;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * NOT registered in {@link ar.scraper.config.RequiredEnvVarsGuard#REQUIRED_VARS} — see
 * {@code RequiredEnvVarsGuardLlmTest}.
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
    public boolean hasApiKey() { return StringUtils.isNotBlank(apiKey); }
}
