package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.api.PageMeta;
import ar.scraper.catalog.PromoPagina;
import ar.scraper.catalog.PromoQueryPort;
import ar.scraper.web.dto.PromoDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Current payment promos of the stores: interest-free installments, discounts and surcharges per medio. */
@RestController
@RequestMapping("/api")
public class PromosController {

    static final int TAMANIO_MAXIMO = 200;

    private final PromoQueryPort promos;

    public PromosController(PromoQueryPort promos) {
        this.promos = promos;
    }

    /**
     * Zero-based pages like {@code /api/data}; {@code size} is clamped to 1..200. {@code medio} is a canonical medio
     * name and matches the medio OR the card network the promo requires.
     */
    @GetMapping("/promos")
    public ResponseEntity<ApiResponse<List<PromoDtos.Promo>>> listar(
            @RequestParam(required = false) String sitio,
            @RequestParam(required = false) String medio,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int numero = Math.min(Math.max(page, 0), Integer.MAX_VALUE - 1);
        int tamanio = Math.min(Math.max(size, 1), TAMANIO_MAXIMO);
        PromoPagina pagina = promos.buscar(sitio, medio, numero, tamanio);
        return ResponseEntity.ok(ApiResponse.page(
                pagina.promos().stream().map(PromoDtos.Promo::of).toList(),
                PageMeta.of(numero, tamanio, pagina.total())));
    }
}
