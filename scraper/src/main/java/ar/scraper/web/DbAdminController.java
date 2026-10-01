package ar.scraper.web;

import ar.scraper.catalog.FavoritosProtegidosException;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.MensajeDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DbAdminController {

    private final ScraperService service;
    private final ar.scraper.ml.MlOutputPort mlOutput;
    private final ar.scraper.catalog.ProductPort productos;
    private final ar.scraper.aggregator.ResultAggregator aggregator;

    public DbAdminController(ScraperService service,
                     ar.scraper.ml.MlOutputPort mlOutput,
                     ar.scraper.catalog.ProductPort productos,
                     ar.scraper.aggregator.ResultAggregator aggregator) {
        this.service = service;
        this.mlOutput = mlOutput;
        this.productos = productos;
        this.aggregator = aggregator;
    }

    @DeleteMapping("/db/productos")
    public ResponseEntity<ApiResponse<MensajeDto>> limpiarProductos() {
        rechazarSiHayScraping();
        try {
            productos.limpiarProductos();
            service.clearLastResult();
            aggregator.clearMlOutput();
            return ResponseEntity.ok(ApiResponse.ok(new MensajeDto("Catálogo eliminado.")));
        } catch (FavoritosProtegidosException e) {
            throw new ApiException(HttpStatus.CONFLICT, "conflicto",
                    "No se puede vaciar el catálogo: " + e.getFavoritosBloqueantes()
                            + " producto(s) favorito(s) todavía existen.");
        }
    }

    @DeleteMapping("/db/ml")
    public ResponseEntity<ApiResponse<MensajeDto>> limpiarMl() {
        rechazarSiHayScraping();
        mlOutput.limpiarMlOutput();
        aggregator.clearMlOutput();
        return ResponseEntity.ok(ApiResponse.ok(new MensajeDto("Datos ML eliminados.")));
    }

    private void rechazarSiHayScraping() {
        if (service.getStatus() == ScraperStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso",
                    "Hay un scraping en curso. Esperá a que termine.");
        }
    }

    // Persistence is PostgreSQL, not a scraper.db file: the file export/import is retired.
    @GetMapping("/db/export")
    public ResponseEntity<ApiResponse<Void>> exportDb() {
        throw new ApiException(HttpStatus.GONE, "recurso_eliminado",
                "DB export de archivo ya no aplica: la persistencia es PostgreSQL, no un archivo scraper.db. Usar pg_dump.");
    }

    @PostMapping(value = "/db/import",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Void>> importDb(@RequestParam("file") org.springframework.web.multipart.MultipartFile upload) {
        throw new ApiException(HttpStatus.GONE, "recurso_eliminado",
                "DB import de archivo ya no aplica: la persistencia es PostgreSQL, no un archivo scraper.db. Usar pg_restore.");
    }
}
