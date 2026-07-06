package ar.scraper.web;

import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
import ar.scraper.cron.CronJobService;
import ar.scraper.db.DatabaseService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints REST del motor de cron jobs (scraper-cronjobs PR1, Phase 6).
 * Sibling DELIBERADO de {@link ApiController} — NO se agregan métodos a
 * {@code ApiController} porque ~20 tests existentes lo instancian
 * directamente con una lista fija de mocks posicionales, y agregar
 * colaboradores nuevos al constructor rompería todos esos tests (ver
 * {@code sdd/scraper-cronjobs/apply-progress}).
 *
 * <p>Todo el cálculo de {@code nextRunAt} vive en {@link CronJobService} —
 * este controller nunca calcula fechas de scheduling, solo valida forma
 * (campos obligatorios, {@code cronExpr} parseable) y delega.</p>
 *
 * <p>Fuera de alcance en este batch (deliberado, ver tasks artifact): un
 * endpoint de detalle de ejecución individual — por eso {@code logOutput} se
 * incluye directo en el listado de ejecuciones (ver {@link #listarEjecuciones})
 * en vez de quedar inalcanzable.</p>
 */
@RestController
@RequestMapping("/api/cron")
public class CronApiController {

    private final CronJobService cronJobService;
    private final DatabaseService db;

    public CronApiController(CronJobService cronJobService, DatabaseService db) {
        this.cronJobService = cronJobService;
        this.db = db;
    }

    // ── GET /api/cron ────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ObjectNode> listar() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("jobs");
        for (CronJob job : db.listCronJobs()) {
            arr.add(jobToJson(job));
        }
        return ResponseEntity.ok(root);
    }

    // ── GET /api/cron/{id} ───────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<ObjectNode> obtener(@PathVariable long id) {
        Optional<CronJob> job = db.getCronJob(id);
        if (job.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(jobToJson(job.get()));
    }

    // ── POST /api/cron ───────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ObjectNode> crear(@RequestBody Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();

        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        Double precioMin = parseDoubleOrNull(body.get("precioMin"));
        Double precioMax = parseDoubleOrNull(body.get("precioMax"));
        List<String> sitios = parseSitios(body.get("sitios"));
        boolean forceRetrain = parseBoolean(body.get("forceRetrain"));
        boolean useGpu = parseBoolean(body.getOrDefault("useGpu", true));
        String cronExpr = String.valueOf(body.getOrDefault("cronExpr", "")).trim();
        boolean enabled = parseBoolean(body.getOrDefault("enabled", true));

        if (name.isBlank() || precioMin == null || precioMax == null || cronExpr.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "name, precioMin, precioMax y cronExpr son obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }
        if (!cronJobService.isValidCronExpr(cronExpr)) {
            resp.put("ok", false);
            resp.put("mensaje", "cronExpr inválido: " + cronExpr);
            return ResponseEntity.badRequest().body(resp);
        }

        long id = cronJobService.createJob(name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled);
        if (id < 0) {
            resp.put("ok", false);
            resp.put("mensaje", "No se pudo crear el cron job");
            return ResponseEntity.badRequest().body(resp);
        }

        return db.getCronJob(id)
                .<ResponseEntity<ObjectNode>>map(job -> ResponseEntity.ok(jobToJson(job)))
                .orElseGet(() -> {
                    resp.put("ok", true);
                    resp.put("id", id);
                    return ResponseEntity.ok(resp);
                });
    }

    // ── PUT /api/cron/{id} ───────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<ObjectNode> actualizar(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();

        if (db.getCronJob(id).isEmpty()) {
            return notFound();
        }

        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        Double precioMin = parseDoubleOrNull(body.get("precioMin"));
        Double precioMax = parseDoubleOrNull(body.get("precioMax"));
        List<String> sitios = parseSitios(body.get("sitios"));
        boolean forceRetrain = parseBoolean(body.get("forceRetrain"));
        boolean useGpu = parseBoolean(body.getOrDefault("useGpu", true));
        String cronExpr = String.valueOf(body.getOrDefault("cronExpr", "")).trim();
        boolean enabled = parseBoolean(body.getOrDefault("enabled", true));

        if (name.isBlank() || precioMin == null || precioMax == null || cronExpr.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "name, precioMin, precioMax y cronExpr son obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }
        if (!cronJobService.isValidCronExpr(cronExpr)) {
            resp.put("ok", false);
            resp.put("mensaje", "cronExpr inválido: " + cronExpr);
            return ResponseEntity.badRequest().body(resp);
        }

        boolean ok = cronJobService.updateJob(id, name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled);
        if (!ok) {
            resp.put("ok", false);
            resp.put("mensaje", "No se pudo actualizar el cron job");
            return ResponseEntity.badRequest().body(resp);
        }

        return db.getCronJob(id)
                .<ResponseEntity<ObjectNode>>map(job -> ResponseEntity.ok(jobToJson(job)))
                .orElseGet(() -> {
                    resp.put("ok", true);
                    return ResponseEntity.ok(resp);
                });
    }

    // ── DELETE /api/cron/{id} ────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<ObjectNode> eliminar(@PathVariable long id) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        boolean borrado = db.deleteCronJob(id);
        if (!borrado) {
            return notFound();
        }
        resp.put("ok", true);
        resp.put("mensaje", "Cron job eliminado");
        return ResponseEntity.ok(resp);
    }

    // ── GET /api/cron/{id}/executions ───────────────────────────────────────

    @GetMapping("/{id}/executions")
    public ResponseEntity<ObjectNode> listarEjecuciones(@PathVariable long id,
            @RequestParam(defaultValue = "50") int limit) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("executions");
        for (CronExecution exec : db.listExecutions(id, limit)) {
            ObjectNode n = arr.addObject();
            n.put("id", exec.id());
            n.put("jobId", exec.jobId());
            n.put("startedAt", exec.startedAt());
            n.put("finishedAt", exec.finishedAt());
            n.put("status", exec.status());
            n.put("skippedReason", exec.skippedReason());
            n.put("durationMs", exec.durationMs());
            n.put("logOutput", exec.logOutput());
        }
        return ResponseEntity.ok(root);
    }

    // ── POST /api/cron/{id}/run-now ──────────────────────────────────────────

    /**
     * Disparo manual. NO BLOQUEANTE — delega en
     * {@link CronJobService#triggerNow(long)}, que despacha
     * {@code CronJobRunner.runJob} en un hilo virtual y retorna de inmediato
     * (un hilo HTTP no puede esperar hasta 2h a que termine un scrape).
     */
    @PostMapping("/{id}/run-now")
    public ResponseEntity<ObjectNode> runNow(@PathVariable long id) {
        CronJobService.RunNowResult result = cronJobService.triggerNow(id);
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        return switch (result) {
            case NOT_FOUND -> notFound();
            case BUSY -> {
                resp.put("ok", false);
                resp.put("mensaje", "Ya hay un scraping en curso");
                yield ResponseEntity.status(409).body(resp);
            }
            case STARTED -> {
                resp.put("ok", true);
                resp.put("mensaje", "Ejecución iniciada");
                yield ResponseEntity.status(202).body(resp);
            }
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<ObjectNode> notFound() {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("ok", false);
        resp.put("mensaje", "Cron job no encontrado");
        return ResponseEntity.status(404).body(resp);
    }

    private ObjectNode jobToJson(CronJob job) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("id", job.id());
        n.put("name", job.name());
        n.put("precioMin", job.precioMin());
        n.put("precioMax", job.precioMax());
        ArrayNode sitiosArr = n.putArray("sitios");
        job.sitios().forEach(sitiosArr::add);
        n.put("forceRetrain", job.forceRetrain());
        n.put("useGpu", job.useGpu());
        n.put("cronExpr", job.cronExpr());
        n.put("enabled", job.enabled());
        n.put("createdAt", job.createdAt());
        n.put("updatedAt", job.updatedAt());
        n.put("lastRunAt", job.lastRunAt());
        n.put("nextRunAt", job.nextRunAt());
        return n;
    }

    private List<String> parseSitios(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private boolean parseBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private Double parseDoubleOrNull(Object v) {
        if (v == null) return null;
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return null; }
    }
}
