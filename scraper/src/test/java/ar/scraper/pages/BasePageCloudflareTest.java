package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** El fixture es una captura 403 real recortada: un challenge nunca es catálogo vacío. */
@Epic("Scraping")
@Feature("Cloudflare detection")
@DisplayName("BasePage — detección de challenge de Cloudflare")
class BasePageCloudflareTest {

    private static String fixture(String path) {
        try (InputStream is = BasePageCloudflareTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("fixture no encontrada: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("La captura real del 403 se reconoce como challenge")
    void detectsRealChallengeCapture() {
        String challenge = fixture("/fixtures/cloudflare/challenge.html");
        assertThat(BasePage.esBloqueoCloudflare(challenge)).isTrue();
    }

    @Test
    @DisplayName("Un listado real no se confunde con un challenge")
    void realListingIsNotAChallenge() {
        String listing = "<html><head><title>Todos los Productos Online al Mejor Precio | FullH4rd</title></head>"
                + "<body><article class=\"results-card\"></article></body></html>";
        assertThat(BasePage.esBloqueoCloudflare(listing)).isFalse();
    }

    @Test
    @DisplayName("Vacío o null no son challenge")
    void blankIsNotAChallenge() {
        assertThat(BasePage.esBloqueoCloudflare("")).isFalse();
        assertThat(BasePage.esBloqueoCloudflare(null)).isFalse();
    }
}