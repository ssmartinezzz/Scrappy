package ar.scraper.web;

/**
 * Neutralises a user-controlled string before it is written to the log. A newline in a logged value
 * lets an anonymous caller forge log lines — a fake {@code [ADMIN]} entry, or a password typed into
 * the username field pushed onto its own line — so every control character becomes a visible marker
 * and the value is capped, since one field must not be able to flood the log.
 */
public final class LogSafe {

    private static final int MAX = 120;

    private LogSafe() {
    }

    /** Returns a one-line, length-capped rendering safe to interpolate into a log message. */
    public static String para(String valor) {
        if (valor == null) {
            return "null";
        }
        String recortado = valor.length() > MAX ? valor.substring(0, MAX) + "…" : valor;
        StringBuilder sb = new StringBuilder(recortado.length());
        for (int i = 0; i < recortado.length(); i++) {
            char c = recortado.charAt(i);
            // Control characters (incl. CR, LF, TAB, NUL, ESC) all collapse to a visible marker.
            sb.append(c < 0x20 || c == 0x7f ? '␢' : c);
        }
        return sb.toString();
    }
}
