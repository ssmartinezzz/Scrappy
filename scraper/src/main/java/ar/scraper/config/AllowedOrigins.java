package ar.scraper.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * The single parsed, startup-validated {@code APP_CORS_ALLOWED_ORIGINS} list.
 *
 * <p>{@link #parsear} and {@link #validar} are the <b>one</b> parser and the
 * <b>one</b> validator — the platform-vocabulary-has-two-copies lesson,
 * applied here to origins instead of platform names. This component calls
 * them from {@link #inicializar}, and {@link ar.scraper.web.CorsConfig} calls
 * the same two static methods from its own field, rather than each keeping
 * its own copy of the split/trim/filter logic. Two independent copies would
 * let a future change — normalising a trailing slash, lowercasing the host —
 * land in one and not the other, and the two lists feed different
 * security decisions (credentialed CORS vs. bootstrap-CSRF admission,
 * frontend-auth-ui Phase 2): a silent disagreement there is security-relevant,
 * not cosmetic. {@code CorsConfig} cannot be constructor-injected with this
 * class as a bean, though — see its own javadoc for why — so the sharing is
 * at the parsing-function level, not the object-instance level.</p>
 *
 * <p>Validation: empty is rejected (the refresh endpoint's credentialed CORS
 * needs an exact list) and {@code "*"} is rejected (forbidden under
 * credentialed CORS, and failing at startup turns a confusing runtime 500
 * into a named variable).</p>
 */
@Component
public class AllowedOrigins {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    private List<String> origenes;

    @PostConstruct
    void inicializar() {
        origenes = parsear(allowedOrigins);
        validar(origenes);
    }

    /** The parsed list, in configured order — first-match-wins mapping order lives in {@code CorsConfig}, not here. */
    public List<String> comoLista() {
        return origenes;
    }

    /** Same list, as the array shape {@code CorsRegistry.allowedOrigins(String...)} wants. */
    public String[] comoArray() {
        return origenes.toArray(new String[0]);
    }

    /**
     * Exact string match — port-sensitive, unlike {@code SameSite}/cookie
     * scoping. Used by the bootstrap-CSRF admission check (Phase 2): an
     * {@code Origin} header is compared against this list byte-for-byte, never
     * as a prefix or a suffix.
     */
    public boolean esPermitido(String origin) {
        return origin != null && origenes.contains(origin);
    }

    /**
     * Splits on commas, trims each entry, drops empty segments. The one
     * parsing algorithm both this component and {@code CorsConfig} run
     * against {@code app.cors.allowed-origins} — public and static so a
     * caller with no bean of this type (a plain-constructed {@code CorsConfig},
     * for instance) can still call it directly.
     */
    public static List<String> parsear(String raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * The one validation both consumers run: empty is rejected, and a
     * wildcard anywhere in the list is rejected — both would otherwise
     * surface as a confusing runtime failure instead of a named startup one.
     */
    public static void validar(List<String> origenes) {
        if (origenes.isEmpty()) {
            throw new IllegalStateException(
                    "APP_CORS_ALLOWED_ORIGINS está vacía. El endpoint de refresco usa CORS con "
                            + "credenciales, que exige una lista de orígenes exacta.");
        }
        for (String origin : origenes) {
            if ("*".equals(origin)) {
                throw new IllegalStateException(
                        "APP_CORS_ALLOWED_ORIGINS no puede ser '*': el endpoint de refresco usa CORS "
                                + "con credenciales, y el comodín está prohibido ahí. Poné la lista "
                                + "exacta de orígenes del frontend.");
            }
        }
    }
}
