package ar.scraper.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Step;

/**
 * Shared cross-cutting Allure {@code @Step} helpers.
 *
 * <p>Deliberately kept small (per design ADR-3): local, private {@code @Step} methods inside
 * each test class remain the default. This class exists ONLY for steps that are genuinely
 * repeated verbatim across many test classes (currently: REST response body serialization
 * across {@code ApiController*Test} classes). Do not add a method here unless a step is
 * provably repeated 3+ times across slices — grow it deliberately, not preemptively.
 */
public final class AllureSteps {

    private AllureSteps() {
    }

    @Step("Serialize response body to JSON")
    public static JsonNode toJson(Object body) {
        return new ObjectMapper().valueToTree(body);
    }
}
