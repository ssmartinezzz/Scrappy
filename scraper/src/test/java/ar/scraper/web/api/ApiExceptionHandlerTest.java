package ar.scraper.web.api;

import ar.scraper.api.ApiError;
import ar.scraper.api.ApiException;
import ar.scraper.identity.ActorResolver;
import ar.scraper.identity.Sujeto;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every failure a handler or the framework can raise leaves as {@code {"error":{code,message}}}.
 * Standalone MockMvc with the real advice: what is under test is the mapping, not the routes.
 */
@Epic("API envelope")
@Feature("Error mapping")
@DisplayName("ApiExceptionHandler — every error is an ApiError")
class ApiExceptionHandlerTest {

    @RestController
    static class Fixture {
        @GetMapping("/t/api-exception")
        String apiException() {
            throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso", "Hay un scraping en curso.",
                    Map.of("estado", "RUNNING")).withHeader("Retry-After", "30");
        }

        @GetMapping("/t/param")
        String param(@RequestParam int page) { return "ok"; }

        @PostMapping("/t/body")
        String body(@RequestBody Map<String, Object> body) { return "ok"; }

        @GetMapping("/t/boom")
        String boom() { throw new IllegalStateException("jdbc:postgresql://db/secret password=hunter2"); }

        @GetMapping("/t/upload")
        String upload() { throw new MaxUploadSizeExceededException(1024); }

        @GetMapping("/t/sin-sujeto")
        String sinSujeto() { Sujeto.de(new ActorResolver()); return "ok"; }

        @GetMapping("/t/solo-get")
        String soloGet() { return "ok"; }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Fixture())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("ApiException keeps its status, code, message, details and headers")
    void apiExceptionIsMappedFaithfully() throws Exception {
        mvc.perform(get("/t/api-exception"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("scrape_en_curso"))
                .andExpect(jsonPath("$.error.message").value("Hay un scraping en curso."))
                .andExpect(jsonPath("$.error.details.estado").value("RUNNING"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("a missing request parameter is 400 solicitud_invalida naming the parameter")
    void missingParameterIs400() throws Exception {
        mvc.perform(get("/t/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("solicitud_invalida"))
                .andExpect(jsonPath("$.error.message").value(containsString("page")));
    }

    @Test
    @DisplayName("a parameter of the wrong type is 400 solicitud_invalida")
    void typeMismatchIs400() throws Exception {
        mvc.perform(get("/t/param").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("solicitud_invalida"))
                .andExpect(jsonPath("$.error.message").value(containsString("page")));
    }

    @Test
    @DisplayName("a malformed JSON body is 400 solicitud_invalida without echoing parser internals")
    void unreadableBodyIs400() throws Exception {
        mvc.perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("solicitud_invalida"))
                .andExpect(jsonPath("$.error.message").value(not(containsString("Unexpected character"))))
                .andExpect(jsonPath("$.error.message").value(not(containsString("com.fasterxml"))));
    }

    @Test
    @DisplayName("a missing body is 400 as well")
    void missingBodyIs400() throws Exception {
        mvc.perform(post("/t/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("solicitud_invalida"));
    }

    @Test
    @DisplayName("a wrong HTTP method is 405 metodo_no_permitido and keeps the Allow header")
    void wrongMethodIs405() throws Exception {
        mvc.perform(post("/t/solo-get"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.error.code").value("metodo_no_permitido"));
    }

    @Test
    @DisplayName("an unsupported media type is 415 media_no_soportada")
    void unsupportedMediaTypeIs415() throws Exception {
        mvc.perform(post("/t/body").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("media_no_soportada"));
    }

    @Test
    @DisplayName("an upload over the limit is 413 payload_demasiado_grande")
    void oversizedUploadIs413() throws Exception {
        mvc.perform(get("/t/upload"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("payload_demasiado_grande"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    @DisplayName("an owner-scoped route with no subject is 401 no_autenticado")
    void missingSubjectIs401() throws Exception {
        mvc.perform(get("/t/sin-sujeto"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("no_autenticado"));
    }

    @Test
    @DisplayName("an unexpected exception is 500 error_interno and never leaks its message")
    void unexpectedExceptionIs500WithoutLeaking() throws Exception {
        mvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("error_interno"))
                .andExpect(jsonPath("$.error.message").value("Error interno del servidor."))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("jdbc:"))));
    }

    @Test
    @DisplayName("an unknown route is 404 no_encontrado")
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/t/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("no_encontrado"));
    }
}
