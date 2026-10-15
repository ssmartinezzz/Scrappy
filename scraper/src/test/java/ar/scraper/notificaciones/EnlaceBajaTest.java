package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EnlaceBaja — the public unsubscribe URL of one account")
class EnlaceBajaTest {

    private final TokenBajaAvisos tokens = new TokenBajaAvisos("un-secreto-de-prueba-de-al-menos-32-bytes!!");

    @Test
    @DisplayName("is the configured public origin plus the signed token, without doubling the slash")
    void armaElEnlace() {
        UUID id = UUID.randomUUID();

        assertThat(new EnlaceBaja(tokens, "https://api.example.com/").para(id))
                .contains("https://api.example.com/api/notificaciones/baja/" + tokens.emitir(id));
        assertThat(new EnlaceBaja(tokens, "https://api.example.com").para(id)).isPresent();
    }

    @Test
    @DisplayName("no origin, or one that is not http(s), means no link at all rather than a broken one")
    void sinOrigenUtil() {
        UUID id = UUID.randomUUID();

        assertThat(new EnlaceBaja(tokens, "").para(id)).isEmpty();
        assertThat(new EnlaceBaja(tokens, "javascript:alert(1)").para(id)).isEmpty();
        assertThat(new EnlaceBaja(tokens, null).para(id)).isEmpty();
    }
}
