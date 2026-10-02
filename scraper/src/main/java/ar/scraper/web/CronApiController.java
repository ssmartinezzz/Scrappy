package ar.scraper.web;

import ar.scraper.scheduling.CronExecution;
import ar.scraper.scheduling.CronJob;
import ar.scraper.scheduling.CronPort;
import ar.scraper.scheduling.CronJobService;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.CronDtos;
import ar.scraper.web.dto.OpResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * A sibling of {@link ApiController} on purpose: adding collaborators to that constructor would
 * break ~20 tests that build it with positional mocks.
 */
@RestController
@RequestMapping("/api/cron")
@RequiredArgsConstructor
public class CronApiController {

    private final CronJobService cronJobService;
    private final CronPort db;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CronDtos.Job>>> listar() {
        return ResponseEntity.ok(ApiResponse.ok(
                db.listCronJobs().stream().map(CronDtos.Job::from).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CronDtos.Job>> obtener(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(CronDtos.Job.from(
                db.getCronJob(id).orElseThrow(CronApiController::noEncontrado))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CronDtos.Job>> crear(@RequestBody Map<String, Object> body) {
        JobForm f = JobForm.parse(body, cronJobService);
        long id = cronJobService.createJob(f.name, f.precioMin, f.precioMax, f.sitios,
                f.forceRetrain, f.useGpu, f.cronExpr, f.enabled);
        if (id < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "No se pudo crear el cron job");
        }
        return ResponseEntity.ok(ApiResponse.ok(CronDtos.Job.from(
                db.getCronJob(id).orElseThrow(CronApiController::creadoSinLectura))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CronDtos.Job>> actualizar(@PathVariable long id,
                                                                @RequestBody Map<String, Object> body) {
        if (db.getCronJob(id).isEmpty()) {
            throw noEncontrado();
        }
        JobForm f = JobForm.parse(body, cronJobService);
        boolean ok = cronJobService.updateJob(id, f.name, f.precioMin, f.precioMax, f.sitios,
                f.forceRetrain, f.useGpu, f.cronExpr, f.enabled);
        if (!ok) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "No se pudo actualizar el cron job");
        }
        return ResponseEntity.ok(ApiResponse.ok(CronDtos.Job.from(
                db.getCronJob(id).orElseThrow(CronApiController::creadoSinLectura))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<OpResult>> eliminar(@PathVariable long id) {
        if (!db.deleteCronJob(id)) {
            throw noEncontrado();
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Cron job eliminado")));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<List<CronDtos.Execution>>> listarEjecuciones(@PathVariable long id,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                db.listExecutions(id, limit).stream().map(CronDtos.Execution::from).toList()));
    }

    /**
     * {@link CronJobService#triggerNow(long)} dispatches the run on a virtual thread and returns at
     * once (an HTTP thread cannot wait up to 2h for a scrape).
     */
    @PostMapping("/{id}/run-now")
    public ResponseEntity<ApiResponse<OpResult>> runNow(@PathVariable long id) {
        return switch (cronJobService.triggerNow(id)) {
            case NOT_FOUND -> throw noEncontrado();
            case BUSY -> throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso",
                    "Ya hay un scraping en curso");
            case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.ok(OpResult.of(true, "Ejecución iniciada")));
        };
    }

    private static ApiException noEncontrado() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "Cron job no encontrado");
    }

    /** The write succeeded but the row cannot be read back: nothing sane to return. */
    private static ApiException creadoSinLectura() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error_interno",
                "El cron job se guardó pero no se pudo leer.");
    }

    private static final class JobForm {
        String name;
        Double precioMin;
        Double precioMax;
        List<String> sitios;
        boolean forceRetrain;
        boolean useGpu;
        String cronExpr;
        boolean enabled;

        static JobForm parse(Map<String, Object> body, CronJobService cronJobService) {
            JobForm f = new JobForm();
            f.name = String.valueOf(body.getOrDefault("name", "")).trim();
            f.precioMin = parseDoubleOrNull(body.get("precioMin"));
            f.precioMax = parseDoubleOrNull(body.get("precioMax"));
            f.sitios = parseSitios(body.get("sitios"));
            f.forceRetrain = parseBoolean(body.get("forceRetrain"));
            f.useGpu = parseBoolean(body.getOrDefault("useGpu", true));
            f.cronExpr = String.valueOf(body.getOrDefault("cronExpr", "")).trim();
            f.enabled = parseBoolean(body.getOrDefault("enabled", true));

            if (f.name.isBlank() || f.precioMin == null || f.precioMax == null || f.cronExpr.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                        "name, precioMin, precioMax y cronExpr son obligatorios");
            }
            if (!cronJobService.isValidCronExpr(f.cronExpr)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                        "cronExpr inválido: " + f.cronExpr);
            }
            return f;
        }

        private static List<String> parseSitios(Object v) {
            if (!(v instanceof List<?> list)) return List.of();
            return list.stream().map(String::valueOf).toList();
        }

        private static boolean parseBoolean(Object v) {
            if (v instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(v));
        }

        private static Double parseDoubleOrNull(Object v) {
            if (v == null) return null;
            try { return Double.parseDouble(String.valueOf(v)); }
            catch (Exception e) { return null; }
        }
    }
}
