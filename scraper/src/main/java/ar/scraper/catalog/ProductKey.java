package ar.scraper.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.lang3.StringUtils;

/**
 * Java la calcula para poder mandar el handle en cada fila del catálogo sin ir a la base. Las dos
 * versiones no pueden divergir en silencio:
 */
public final class ProductKey {

    private static final int LARGO = 16;

    private ProductKey() {}

    public static String of(String url) {
        if (StringUtils.isEmpty(url)) return "";
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hash = md5.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                  .append(Character.forDigit(b & 0xF, 16));
            return hex.substring(0, LARGO);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 no disponible en esta JVM", e);
        }
    }
}
