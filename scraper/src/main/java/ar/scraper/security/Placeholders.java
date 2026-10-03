package ar.scraper.security;

import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * The single most likely way this project ships with a world-known secret is somebody copying an
 * {@code .env.example} and never editing it. Every example value for a secret follows one of two
 * conventions — {@code cambiame-...} or {@code replace-me-...} — so refusing anything carrying
 * either marker, plus the exact strings currently shipped, closes that path for the admin password,
 * the CLI service-account password and the JWT signing secret alike.
 *
 * <p>A real secret containing one of these markers is vanishingly unlikely, and if one does, it is a
 * secret that reads like an unedited placeholder and is better rejected than trusted.</p>
 */
final class Placeholders {

    /** Exact values shipped in {@code .env.example} / {@code docker.env.example} today. */
    private static final Set<String> EXACTOS = Set.of(
            "cambiame-por-una-password-real",
            "replace-me-with-random-too",
            "replace-me-with-at-least-32-bytes-of-random");

    /** Substrings every shipped example value starts with; caught case-insensitively. */
    private static final Set<String> MARCADORES = Set.of("cambiame", "replace-me");

    private Placeholders() {
    }

    /** True when {@code valor} looks like an unedited example secret. */
    static boolean esDeEjemplo(String valor) {
        if (StringUtils.isBlank(valor)) {
            return false;
        }
        String normalizado = valor.toLowerCase(Locale.ROOT);
        if (EXACTOS.contains(normalizado)) {
            return true;
        }
        return MARCADORES.stream().anyMatch(normalizado::contains);
    }
}
