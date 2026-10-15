package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenBajaAvisos — a signed, unguessable, stateless token per account")
class TokenBajaAvisosTest {

    private static final String SECRETO = "un-secreto-de-prueba-de-al-menos-32-bytes!!";
    private final TokenBajaAvisos tokens = new TokenBajaAvisos(SECRETO);

    @Test
    @DisplayName("a token verifies back to the account it was issued for, and is the same every time")
    void ida_y_vuelta() {
        UUID id = UUID.randomUUID();

        String token = tokens.emitir(id);

        assertThat(token).matches("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}");
        assertThat(tokens.verificar(token)).contains(id);
        assertThat(tokens.emitir(id)).isEqualTo(token);
    }

    @Test
    @DisplayName("a token for one account does not verify as another: swapping the id breaks the signature")
    void cambiarElIdLoInvalida() {
        String delOtro = tokens.emitir(UUID.randomUUID());
        String mio = tokens.emitir(UUID.randomUUID());

        String injertado = parteId(delOtro) + "." + parteFirma(mio);

        assertThat(tokens.verificar(injertado)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with another secret, a flipped signature bit or garbage never verifies")
    void rechazaLoAjeno() {
        UUID id = UUID.randomUUID();
        String token = tokens.emitir(id);
        int medio = token.indexOf('.') + 10;
        String adulterado = token.substring(0, medio) + (token.charAt(medio) == 'A' ? "B" : "A") + token.substring(medio + 1);
        String noCanonico = token.substring(0, token.length() - 1) + "B";

        assertThat(new TokenBajaAvisos("otro-secreto-distinto-de-al-menos-32-bytes!").verificar(token)).isEmpty();
        assertThat(tokens.verificar(adulterado)).isEmpty();
        assertThat(tokens.verificar("basura")).isEmpty();
        if (!noCanonico.equals(token)) {
            assertThat(tokens.verificar(noCanonico)).as("only the canonical encoding is accepted").isEmpty();
        }
        assertThat(tokens.verificar(null)).isEmpty();
        assertThat(tokens.verificar("")).isEmpty();
    }

    @Test
    @DisplayName("the key is derived with a domain label: the signature is NOT a plain HMAC of the id under the secret")
    void claveDerivadaConEtiqueta() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] idBytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRETO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String directa = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(idBytes));

        assertThat(parteFirma(tokens.emitir(id))).isNotEqualTo(directa);
    }

    @Test
    @DisplayName("the shape check accepts only what emitir produces")
    void forma() {
        assertThat(TokenBajaAvisos.tieneForma(tokens.emitir(UUID.randomUUID()))).isTrue();
        assertThat(TokenBajaAvisos.tieneForma("abc")).isFalse();
        assertThat(TokenBajaAvisos.tieneForma("<script>.<script>")).isFalse();
        assertThat(TokenBajaAvisos.tieneForma(null)).isFalse();
    }

    private static String parteId(String token) {
        return token.substring(0, token.indexOf('.'));
    }

    private static String parteFirma(String token) {
        return token.substring(token.indexOf('.') + 1);
    }
}
