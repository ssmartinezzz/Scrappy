package ar.scraper.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Handle corto y estable de un producto, derivado de su URL.
 *
 * <p>Es un alias de presentación: la identidad del producto sigue siendo
 * {@code productos.url}, que es la clave primaria. Esto existe sólo para que
 * una ruta sea {@code /historial/6f1c2b...} en vez de arrastrar la URL entera
 * percent-encodeada.</p>
 *
 * <p><b>Esta expresión tiene un gemelo en SQL</b>: la columna generada
 * {@code productos.producto_key} de {@code V25}, que es la que tiene el índice
 * y resuelve la búsqueda. Java la calcula para poder mandar el handle en cada
 * fila del catálogo sin ir a la base. Las dos versiones no pueden divergir en
 * silencio: {@code ProductKeyParityTest} las corre contra un Postgres real
 * sobre el mismo set de URLs — mismo criterio que {@code PrecioParser} y
 * {@code sp_parse_precio_ar}, que comparten fixture.</p>
 *
 * <p>MD5 acá <b>no es un uso criptográfico</b>. Es un identificador opaco: se
 * le pide ser determinístico y estar bien distribuido, nada más. Se eligió
 * sobre SHA-256 porque {@code md5(text)} es IMMUTABLE en Postgres y por lo
 * tanto usable en una columna generada; {@code sha256()} habría necesitado un
 * cast de {@code text} a {@code bytea} que no lo es.</p>
 */
final class ProductKey {

    /** 16 hex = 64 bits. El porqué de ese largo está en el header de `V25`. */
    private static final int LARGO = 16;

    private ProductKey() {}

    /** {@code null} o vacío devuelven {@code ""} — abstención, nunca un centinela (`CODE-5`). */
    static String of(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hash = md5.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                  .append(Character.forDigit(b & 0xF, 16));
            return hex.substring(0, LARGO);
        } catch (NoSuchAlgorithmException e) {
            // MD5 es obligatorio en toda JVM (JLS/JCA). Si falta, algo mucho
            // más grave pasa que un handle corto.
            throw new IllegalStateException("MD5 no disponible en esta JVM", e);
        }
    }
}
