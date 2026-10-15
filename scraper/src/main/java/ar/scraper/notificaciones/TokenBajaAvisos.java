package ar.scraper.notificaciones;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The unsubscribe link carries {@code id.firma}: nothing is stored, and only this process can mint a
 * valid one. The signing key is derived from the JWT secret under its own label, so a signature made
 * here can never be replayed as, or forged from, anything else signed with that secret.
 */
@Component
public class TokenBajaAvisos {

    private static final String ALGORITMO = "HmacSHA256";
    private static final String ETIQUETA = "scrappy/notificaciones/baja/v1";
    private static final Pattern FORMA = Pattern.compile("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] clave;

    public TokenBajaAvisos(@Value("${auth.jwt.secret}") String secreto) {
        this.clave = hmac(secreto.getBytes(StandardCharsets.UTF_8), ETIQUETA.getBytes(StandardCharsets.UTF_8));
    }

    public String emitir(UUID usuarioId) {
        byte[] id = ByteBuffer.allocate(16)
                .putLong(usuarioId.getMostSignificantBits()).putLong(usuarioId.getLeastSignificantBits()).array();
        return B64.encodeToString(id) + "." + B64.encodeToString(hmac(clave, id));
    }

    /** Empty for anything that is not exactly what {@link #emitir} produced; the caller cannot tell why. */
    public Optional<UUID> verificar(String token) {
        if (!tieneForma(token)) {
            return Optional.empty();
        }
        int punto = token.indexOf('.');
        byte[] id = Base64.getUrlDecoder().decode(token.substring(0, punto));
        byte[] firma = Base64.getUrlDecoder().decode(token.substring(punto + 1));
        if (!MessageDigest.isEqual(hmac(clave, id), firma) || !token.equals(B64.encodeToString(id) + "." + B64.encodeToString(firma))) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(id);
        return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
    }

    public static boolean tieneForma(String token) {
        return token != null && FORMA.matcher(token).matches();
    }

    private static byte[] hmac(byte[] clave, byte[] datos) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(clave, ALGORITMO));
            return mac.doFinal(datos);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
