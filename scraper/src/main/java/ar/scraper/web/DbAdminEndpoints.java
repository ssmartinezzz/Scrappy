package ar.scraper.web;

import org.springframework.http.ResponseEntity;

/**
 * Destructive catalog/ML maintenance and the retired file export/import.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class DbAdminEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(DbAdminEndpoints.class);

    private final ScraperService service;
    private final ar.scraper.db.DatabaseService db;
    private final ar.scraper.aggregator.ResultAggregator aggregator;

    DbAdminEndpoints(ScraperService service,
                     ar.scraper.db.DatabaseService db,
                     ar.scraper.aggregator.ResultAggregator aggregator) {
        this.service = service;
        this.db = db;
        this.aggregator = aggregator;
    }

    ResponseEntity<String> limpiarProductos() {
        if (service.getStatus() == ScraperService.ScraperStatus.RUNNING) {
            return ResponseEntity.status(409).body("Hay un scraping en curso. Esperá a que termine.");
        }
        try {
            db.limpiarProductos();
            service.clearLastResult();
            aggregator.clearMlOutput();
            return ResponseEntity.ok("Catálogo eliminado.");
        } catch (ar.scraper.db.FavoritosProtegidosException e) {
            // normalize-db-schema-fks-1nf, slice A.1 (design D9): the FK RESTRICT
            // on favoritos.url (V4) surfaces here as an actionable 409 instead of
            // a raw FK-violation 500. No ?force= override — deliberate (spec
            // "Catalog-wipe contract").
            return ResponseEntity.status(409).body(
                    "No se puede vaciar el catálogo: " + e.getFavoritosBloqueantes()
                            + " producto(s) favorito(s) todavía existen.");
        } catch (Exception e) {
            LOG.error("[API] Error al limpiar productos", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    ResponseEntity<String> limpiarMl() {
        if (service.getStatus() == ScraperService.ScraperStatus.RUNNING) {
            return ResponseEntity.status(409).body("Hay un scraping en curso. Esperá a que termine.");
        }
        try {
            db.limpiarMlOutput();
            aggregator.clearMlOutput();
            return ResponseEntity.ok("Datos ML eliminados.");
        } catch (Exception e) {
            LOG.error("[API] Error al limpiar ML", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    // decouple-services-postgres Batch 3 (task 3.6): the backend no longer
    // resolves a filesystem SQLite path — persistence lives in Postgres
    // (Batch 1, design D1-D3). The old file-based export/import (which
    // downloaded/replaced a `scraper.db` file, backed by the removed
    // `encontrarDbFile()`) has no equivalent for a networked Postgres
    // instance and is retired here rather than left silently broken.
    // A Postgres-native backup/restore flow (pg_dump/pg_restore, an
    // installer/ops concern) is out of scope for this change; these
    // endpoints now answer honestly instead of pretending to work.
    ResponseEntity<Object> exportDb() {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
            .body(java.util.Map.of("error",
                "DB export de archivo ya no aplica: la persistencia es PostgreSQL, no un archivo scraper.db. Usar pg_dump."));
    }

    ResponseEntity<Object> importDb(org.springframework.web.multipart.MultipartFile upload) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
            .body(java.util.Map.of("error",
                "DB import de archivo ya no aplica: la persistencia es PostgreSQL, no un archivo scraper.db. Usar pg_restore."));
    }
}
