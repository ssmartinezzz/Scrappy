package ar.scraper.agent;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic recognition of requests the agent must refuse before any model call:
 * creating, changing, deleting or assigning users, roles or permissions, and creating,
 * scheduling, launching or modifying cron jobs or scrapes. The tools are already
 * read-only; this makes the refusal reliable instead of depending on the grounding
 * gate, which used to discard a correct model refusal as "ungrounded".
 *
 * <p>Precision beats recall: a false positive blocks a legitimate product search, a
 * false negative only reaches a model that has no tool to do it anyway. So a match
 * needs BOTH an action word and a restricted object within {@link #WINDOW} tokens after
 * it — "dame un mouse para usuario zurdo" (object four tokens away) is a product query,
 * "dame permisos de administrador" is not. A bare question ("¿cuántos usuarios tiene el
 * catálogo?") has no action word and is deliberately NOT restricted: it is a read, and a
 * refusal that says "no puedo modificar" would misdescribe it.</p>
 */
final class RestrictedIntents {

    private RestrictedIntents() {}

    /** How many tokens after an action word a restricted object may sit. */
    private static final int WINDOW = 3;

    private static final Set<String> ACTIONS = Set.of(
            // ES: imperative / infinitive / subjunctive forms people actually type
            "crea", "crear", "cree", "borra", "borrar", "borres", "elimina", "eliminar", "elimines",
            "modifica", "modificar", "modifiques", "cambia", "cambiar", "cambiale", "cambies",
            "asigna", "asignar", "asignale", "asignes", "da", "dame", "dale", "des", "dar", "otorga",
            "otorgar", "quita", "quitar", "quitale", "saca", "sacar", "revoca", "revocar", "edita",
            "editar", "actualiza", "actualizar", "agrega", "agregar", "agregale", "anade", "anadir",
            "hace", "hacer", "haceme", "hazme", "hagas", "programa", "programar", "agenda", "agendar",
            "lanza", "lanzar", "ejecuta", "ejecutar", "inicia", "iniciar", "corre", "correr",
            "dispara", "disparar", "habilita", "habilitar", "deshabilita", "deshabilitar",
            "desactiva", "desactivar", "activa", "activar", "registra", "registrar",
            // EN
            "create", "delete", "remove", "add", "give", "grant", "make", "assign", "change", "edit",
            "modify", "update", "schedule", "launch", "run", "start", "trigger", "revoke", "disable",
            "enable");

    private static final Set<String> OBJECTS = Set.of(
            "usuario", "usuarios", "user", "users", "cuenta", "cuentas", "account", "accounts",
            "rol", "roles", "role", "permiso", "permisos", "permission", "permissions",
            "admin", "admins", "administrador", "administradores", "administrator", "rights", "privilegios",
            "cron", "crons", "cronjob", "cronjobs", "scrape", "scrapes", "scraping",
            "tarea", "tareas", "job", "jobs");

    /** Verbs that are already the whole request: "scrapeá todos los sitios". */
    private static final Set<String> SCRAPE_VERBS = Set.of(
            "scrapea", "scrapear", "scrapee", "scrapeen", "scrapeame", "scrapeale", "scrapeo");

    static boolean matches(String rawUserText) {
        if (rawUserText == null) return false;
        String normalized = AccentStripper.strip(rawUserText.trim().toLowerCase(Locale.ROOT));
        normalized = normalized.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return false;
        String[] t = normalized.split(" ");

        for (int i = 0; i < t.length; i++) {
            // "una pc para scrapear datos" is a use case for a product, not an order.
            if (SCRAPE_VERBS.contains(t[i]) && !(i > 0 && t[i - 1].equals("para"))) return true;
            if (!ACTIONS.contains(t[i])) continue;
            for (int j = i + 1; j <= Math.min(i + WINDOW, t.length - 1); j++) {
                if (OBJECTS.contains(t[j])) return true;
            }
        }
        return false;
    }
}
