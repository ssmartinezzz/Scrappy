package ar.scraper.api;

import ar.scraper.model.Product;
import ar.scraper.web.dto.CatalogoDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("API envelope")
@Feature("Serialization")
@DisplayName("ApiResponse / PageMeta / ApiError — wire shape")
class ApiEnvelopeSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("a single-value response carries only `data`; `page` is omitted, not null")
    void okOmitsPage() {
        JsonNode json = mapper.valueToTree(ApiResponse.ok(Map.of("x", 1)));

        assertThat(json.fieldNames()).toIterable().containsExactly("data");
        assertThat(json.get("data").get("x").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a list response carries `data` (the bare array) and the 0-based `page` block")
    void pageCarriesMeta() {
        JsonNode json = mapper.valueToTree(ApiResponse.page(List.of("a", "b"), PageMeta.of(2, 24, 50)));

        assertThat(json.get("data").isArray()).isTrue();
        assertThat(json.get("data")).hasSize(2);
        JsonNode page = json.get("page");
        assertThat(page.get("number").asInt()).isEqualTo(2);
        assertThat(page.get("size").asInt()).isEqualTo(24);
        assertThat(page.get("total").asLong()).isEqualTo(50);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("an empty list is still `data: []`, never absent")
    void emptyListIsAnEmptyArray() {
        JsonNode json = mapper.valueToTree(ApiResponse.page(List.of(), PageMeta.of(0, 24, 0)));

        assertThat(json.get("data").isArray()).isTrue();
        assertThat(json.get("data")).isEmpty();
        assertThat(json.get("page").get("totalPages").asInt()).isZero();
    }

    @Test
    @DisplayName("totalPages rounds up, and a non-positive size yields zero instead of dividing by zero")
    void totalPagesArithmetic() {
        assertThat(PageMeta.of(0, 10, 10).getTotalPages()).isEqualTo(1);
        assertThat(PageMeta.of(0, 10, 11).getTotalPages()).isEqualTo(2);
        assertThat(PageMeta.of(0, 10, 0).getTotalPages()).isZero();
        assertThat(PageMeta.of(0, 0, 5).getTotalPages()).isZero();
        assertThat(PageMeta.of(0, -1, 5).getTotalPages()).isZero();
    }

    @Test
    @DisplayName("an error carries {error:{code,message}} and omits `details` when there are none")
    void errorShape() {
        JsonNode json = mapper.valueToTree(ApiError.of("no_encontrado", "No existe."));

        assertThat(json.fieldNames()).toIterable().containsExactly("error");
        assertThat(json.get("error").get("code").asText()).isEqualTo("no_encontrado");
        assertThat(json.get("error").get("message").asText()).isEqualTo("No existe.");
        assertThat(json.get("error").has("details")).isFalse();
    }

    @Test
    @DisplayName("an error keeps `details` when the handler supplies them")
    void errorWithDetails() {
        JsonNode json = mapper.valueToTree(ApiError.of("conflicto_stale", "Cambió.", Map.of("actual", "Buzo")));

        assertThat(json.get("error").get("details").get("actual").asText()).isEqualTo("Buzo");
    }

    @Test
    @DisplayName("every field of every response DTO is exposed under its own name")
    void everyDtoFieldKeepsItsJsonName() throws Exception {
        // Lombok names the getter of `zScore` getZScore(), which Jackson reads as "zscore": the
        // frontend reads zScore. Instantiating each DTO and comparing declared fields to the
        // serialized properties catches that whole class of silent rename.
        List<String> mismatches = new ArrayList<>();
        for (Class<?> dto : dtoClasses()) {
            if (dto.getSimpleName().endsWith("Builder")
                    && java.util.Arrays.stream(dto.getDeclaredMethods()).anyMatch(m -> m.getName().equals("build"))) {
                continue; // Lombok's generated builder, not a payload
            }
            Object instance;
            try {
                var ctor = dto.getDeclaredConstructor();
                ctor.setAccessible(true);
                instance = ctor.newInstance();
            } catch (NoSuchMethodException e) {
                continue; // records and builder-only types serialize by component name
            }
            if (java.util.Arrays.stream(dto.getDeclaredFields()).allMatch(f -> Modifier.isStatic(f.getModifiers()))) {
                continue; // a holder of nested DTOs, nothing to serialize itself
            }
            Set<String> props = new TreeSet<>();
            mapper.valueToTree(instance).fieldNames().forEachRemaining(props::add);
            for (var field : dto.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (field.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) continue;
                var include = field.getAnnotation(com.fasterxml.jackson.annotation.JsonInclude.class);
                if (include != null && include.value() == com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) {
                    continue; // omitted while null by design; the row-shape test covers the populated case
                }
                var renamed = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                String expected = renamed != null && !renamed.value().isEmpty() ? renamed.value() : field.getName();
                if (!props.contains(expected) && !nullAndOmitted(dto)) {
                    mismatches.add(dto.getName() + "#" + field.getName() + " -> expected \"" + expected + "\" in " + props);
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    /** NON_NULL DTOs legitimately omit unset fields; their names are covered by the row-parity test. */
    private static boolean nullAndOmitted(Class<?> dto) {
        var include = dto.getAnnotation(com.fasterxml.jackson.annotation.JsonInclude.class);
        return include != null && include.value() == com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
    }

    private static List<Class<?>> dtoClasses() throws Exception {
        java.util.Set<Class<?>> out = new java.util.LinkedHashSet<>();
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var factory = new org.springframework.core.type.classreading.CachingMetadataReaderFactory(resolver);
        for (var res : resolver.getResources("classpath*:ar/scraper/web/dto/*.class")) {
            String name = factory.getMetadataReader(res).getClassMetadata().getClassName();
            Class<?> cls = Class.forName(name);
            out.add(cls);
            for (Class<?> inner : cls.getDeclaredClasses()) out.add(inner);
        }
        return new ArrayList<>(out);
    }

    @Test
    @DisplayName("/api/data rows keep the keys the frontend has always read (including ml.zScore)")
    void productRowKeepsTheHistoricalKeys() {
        Product p = new Product("Sitio", "Remera Negra", 15990, 19990.0, "https://t/remera", "img",
                "Remera", "unisex", List.of("M", "L"),
                new Product.MlScore(80, List.of("all_time_low"), true, "bajando", 12, 0.5, "premium"),
                "Nike", "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);

        JsonNode fila = mapper.valueToTree(CatalogoDtos.ProductoRow.of(p, "12 cuotas"));

        assertThat(keysDe(fila)).containsExactlyInAnyOrder(
                "key", "sitio", "nombre", "precio", "precioOrig", "descuento", "url", "img", "categoria",
                "genero", "marca", "rubro", "gymrat", "marcaPremium", "cantidadUnidades", "esPack",
                "precioUnitario", "sub_categoria", "talles", "ml", "senal", "senalFinanciacion");
        assertThat(keysDe(fila.get("ml"))).containsExactlyInAnyOrder(
                "badge", "badges", "scoreP", "ofertaReal", "tendencia", "pctil", "zScore", "segment");
        assertThat(fila.get("ml").get("zScore").asDouble()).isEqualTo(0.5);
        assertThat(keysDe(fila.get("senal"))).containsExactlyInAnyOrder("senal", "scoreCompra", "confianza");
        assertThat(keysDe(fila.get("senalFinanciacion")))
                .containsExactlyInAnyOrder("senal", "ahorroReal", "vp", "presetLabel");
        assertThat(fila.get("senalFinanciacion").get("presetLabel").asText()).isEqualTo("12 cuotas");
    }

    private static Set<String> keysDe(JsonNode n) {
        Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
