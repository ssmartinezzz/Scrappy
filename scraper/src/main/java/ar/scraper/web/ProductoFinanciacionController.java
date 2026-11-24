package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.catalog.ProductKey;
import ar.scraper.web.dto.ProductoFinanciacionDtos.Plan;
import ar.scraper.web.dto.ProductoFinanciacionDtos.Respuesta;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.temporal.ChronoUnit;

/** Installment plans of one product, fetched live from its store page (cached for minutes). */
@RestController
@RequestMapping("/api")
public class ProductoFinanciacionController {

    private final FinanciacionProductoService servicio;

    public ProductoFinanciacionController(FinanciacionProductoService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/producto/{key}/financiacion")
    public ResponseEntity<ApiResponse<Respuesta>> financiacion(@PathVariable String key) {
        var consulta = servicio.consultar(key);
        var producto = consulta.producto();
        var detalle = consulta.detalle();
        return ResponseEntity.ok(ApiResponse.ok(new Respuesta(
                ProductKey.of(producto.url()), producto.url(), producto.sitio(),
                consulta.consultadoAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                detalle.precioContado(), detalle.precioLista(), detalle.stockWeb(), detalle.stockLocal(),
                detalle.planes().stream().map(p -> new Plan(p.cuotas(), p.montoCuota(), p.total(),
                        p.aproximado(), p.medio(), p.sinInteres())).toList())));
    }
}
