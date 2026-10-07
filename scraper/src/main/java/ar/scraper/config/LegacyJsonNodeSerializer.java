package ar.scraper.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Boot 4 writes HTTP bodies with Jackson 3, but the code builds its JSON trees with Jackson 2.
 * Jackson 3 does not know a Jackson 2 {@link JsonNode} and would serialize it as a bean
 * ({@code nodeType}, {@code array}, ...), so the node is written verbatim instead.
 */
@JacksonComponent
class LegacyJsonNodeSerializer extends ValueSerializer<JsonNode> {

    @Override
    public void serialize(JsonNode value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeRawValue(value.toString());
    }
}
