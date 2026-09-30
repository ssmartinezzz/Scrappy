package ar.scraper.agent;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic recognition of requests the agent must refuse before any model call: creating,
 * changing, deleting or assigning users, roles or permissions, and creating, scheduling, launching
 * or modifying cron jobs or scrapes.
 */
final class RestrictedIntents {

    private RestrictedIntents() {}

    /** How many tokens after an action word a restricted object may sit. */
    private static final int WINDOW = 3;

    private static final Set<String> ACTIONS = Set.of(
            "crea", "crear", "cree", "borra", "borrar", "borres", "elimina", "eliminar", "elimines",
            "modifica", "modificar", "modifiques", "cambia", "cambiar", "cambiale", "cambies",
            "asigna", "asignar", "asignale", "asignes", "da", "dame", "dale", "des", "dar", "otorga",
            "otorgar", "quita", "quitar", "quitale", "saca", "sacar", "revoca", "revocar", "edita",
            "editar", "actualiza", "actualizar", "agrega", "agregar", "agregale", "anade", "anadir",
            "hace", "hacer", "haceme", "hazme", "hagas", "programa", "programar", "agenda", "agendar",
            "lanza", "lanzar", "ejecuta", "ejecutar", "inicia", "iniciar", "corre", "correr",
            "dispara", "disparar", "habilita", "habilitar", "deshabilita", "deshabilitar",
            "desactiva", "desactivar", "activa", "activar", "registra", "registrar",
            "create", "delete", "remove", "add", "give", "grant", "make", "assign", "change", "edit",
            "modify", "update", "schedule", "launch", "run", "start", "trigger", "revoke", "disable",
            "enable");

    private static final Set<String> OBJECTS = Set.of(
            "usuario", "usuarios", "user", "users", "cuenta", "cuentas", "account", "accounts",
            "rol", "roles", "role", "permiso", "permisos", "permission", "permissions",
            "admin", "admins", "administrador", "administradores", "administrator", "rights", "privilegios",
            "cron", "crons", "cronjob", "cronjobs", "scrape", "scrapes", "scraping",
            "tarea", "tareas", "job", "jobs");

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
