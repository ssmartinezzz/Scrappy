package ar.scraper.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Two independent copies would let a future change — normalising a trailing slash, lowercasing the
 * host — land in one and not the other, and the two lists feed different security decisions: a
 * silent disagreement there is security-relevant, not cosmetic.
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

    public List<String> comoLista() {
        return origenes;
    }

    public String[] comoArray() {
        return origenes.toArray(new String[0]);
    }

    /** Exact string match — port-sensitive, unlike {@code SameSite}/cookie scoping. */
    public boolean esPermitido(String origin) {
        return origin != null && origenes.contains(origin);
    }

    /**
     * The one parsing algorithm both this component and {@code CorsConfig} run against
     * {@code app.cors.allowed-origins} — public and static so a caller with no bean of this type (a
     * plain-constructed {@code CorsConfig}, for instance) can still call it directly.
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
     * The one validation both consumers run: empty is rejected, and a wildcard anywhere in the list
     * is rejected — both would otherwise surface as a confusing runtime failure instead of a named
     * startup one.
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
