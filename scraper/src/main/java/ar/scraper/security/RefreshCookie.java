package ar.scraper.security;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The one cookie this API has, and the reasoning for every flag on it. Everything else in this
 * backend is a bearer header, deliberately: a header is never attached automatically, so no
 * cross-site page can make the browser send it.
 */
public final class RefreshCookie {

    public static final String NOMBRE = "refresh";
    public static final String PATH = "/api/auth/refresh";

    private RefreshCookie() {
    }

    public static ResponseCookie emitir(String rawToken, Duration vida) {
        return base(rawToken).maxAge(vida).build();
    }

    /** Same name and path, zero age — anything else leaves the cookie in place. */
    public static ResponseCookie limpiar() {
        return base("").maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String valor) {
        return ResponseCookie.from(NOMBRE, valor)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH);
    }
}
