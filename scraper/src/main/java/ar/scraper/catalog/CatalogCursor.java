package ar.scraper.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Keyset position in a price-ordered catalog read: the {@code (precio, url)} of the last row served. Opaque on the
 * wire (base64url of {@code precio|url}); {@code url} may itself contain {@code |}, so only the first one splits.
 */
public record CatalogCursor(double precio, String url) {

    public static boolean admite(String orden) {
        return "precio_asc".equals(orden) || "precio_desc".equals(orden);
    }

    public String codificar() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((Double.toString(precio) + "|" + url).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the text is not a cursor this class produced */
    public static CatalogCursor decodificar(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int barra = raw.indexOf('|');
        if (barra < 1 || barra == raw.length() - 1) throw new IllegalArgumentException("cursor inválido");
        double precio = Double.parseDouble(raw.substring(0, barra));
        if (Double.isNaN(precio) || Double.isInfinite(precio)) throw new IllegalArgumentException("cursor inválido");
        return new CatalogCursor(precio, raw.substring(barra + 1));
    }
}
