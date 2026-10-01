package ar.scraper.web.api;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The container's own error dispatch ({@code /error}) speaks the same envelope as everything else. */
@Epic("API envelope")
@Feature("Error mapping")
@DisplayName("ApiErrorController — /error")
class ApiErrorControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiErrorController()).build();

    @Test
    @DisplayName("keeps the status the container recorded and maps it to a stable code")
    void keepsTheRecordedStatus() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("no_encontrado"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("no_autenticado"));
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("sin_permiso"));
    }

    @Test
    @DisplayName("a 5xx never carries the exception message the container recorded")
    void fiveHundredIsGeneric() throws Exception {
        mvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "NullPointerException at Foo.java:12"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("error_interno"))
                .andExpect(jsonPath("$.error.message").value("Error interno del servidor."))
                .andExpect(content().string(not(containsString("NullPointer"))));
    }

    @Test
    @DisplayName("with no recorded status it answers 500 error_interno")
    void noStatusIsA500() throws Exception {
        mvc.perform(get("/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("error_interno"));
    }

    @Test
    @DisplayName("answers JSON even when the original request asked for HTML")
    void answersJsonForABrowserAccept() throws Exception {
        mvc.perform(get("/error").accept("text/html").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("no_encontrado"));
    }
}
