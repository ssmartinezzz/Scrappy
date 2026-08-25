package ar.scraper.security;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The one cookie this API has, and the reasoning for every flag on it.
 *
 * <p>Everything else in this backend is a bearer header, deliberately: a header
 * is never attached automatically, so no cross-site page can make the browser
 * send it. The refresh token is the exception, and it earns the exception by
 * being the one credential that has to survive a page reload. An access token
 * held in memory dies on refresh (F5); one kept in {@code localStorage} survives
 * but is readable by any script that gets injected. An {@code HttpOnly} cookie
 * is the only option that does both — survives the reload, invisible to
 * JavaScript.</p>
 *
 * <ul>
 *   <li><b>HttpOnly</b> — script cannot read it, so an XSS foothold cannot walk
 *       away with a fourteen-day credential.</li>
 *   <li><b>Secure</b> — never sent over plain HTTP. Browsers exempt
 *       {@code localhost}, which is why local development still works while a
 *       real deployment structurally requires TLS.</li>
 *   <li><b>SameSite=Strict</b> — not attached to cross-site requests at all.
 *       Necessary but <b>not sufficient</b> here: same-site ignores the port, so
 *       any page on any other {@code localhost} port counts as same-site with
 *       this app. That gap is what the CSRF nonce closes.</li>
 *   <li><b>Path=/api/auth/refresh</b> — the browser attaches it to exactly one
 *       endpoint instead of all ~70. The narrow path is what makes this a
 *       scoped exception to "no cookies" rather than a reversal of it.</li>
 * </ul>
 *
 * <p>Clearing repeats the same name and path, because a cookie is identified by
 * that triple: a {@code Max-Age=0} sent on a different path deletes nothing and
 * looks like it worked.</p>
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
