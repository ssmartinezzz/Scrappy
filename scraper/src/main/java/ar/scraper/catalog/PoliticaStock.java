package ar.scraper.catalog;

import ar.scraper.model.Product;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-time stock policy: the configured vigencia plus a clock. Every reader that exposes or filters
 * on stock asks this, so "stale reads as unknown" has one owner.
 */
public final class PoliticaStock {

    public static final Duration VIGENCIA_PREDETERMINADA = Duration.ofDays(7);

    private final Clock clock;
    private final Duration vigencia;

    public PoliticaStock(Clock clock, Duration vigencia) {
        this.clock = clock;
        this.vigencia = vigencia;
    }

    public static PoliticaStock porDefecto() {
        return new PoliticaStock(Clock.systemUTC(), VIGENCIA_PREDETERMINADA);
    }

    public EstadoStock estado(Product p) {
        return EstadoStock.de(p.enStock(), p.stockChequeadoAt(), clock.instant(), vigencia);
    }

    /**
     * SQL bound for the filter: rows checked before this instant are stale, so they never count as sold out.
     */
    public Instant corte() {
        return clock.instant().minus(vigencia);
    }

    /** Candidates for something meant for buying: confirmed sold-out rows leave, unknown ones stay (CODE-5). */
    public List<Product> candidatos(List<Product> productos, boolean incluirSinStock) {
        if (incluirSinStock) return productos;
        // Builders pass the whole catalog per request: read the clock once, and only copy when a row leaves.
        Instant ahora = clock.instant();
        int primero = -1;
        for (int i = 0; i < productos.size(); i++) {
            if (sinStock(productos.get(i), ahora)) { primero = i; break; }
        }
        if (primero < 0) return productos;
        List<Product> quedan = new ArrayList<>(productos.subList(0, primero));
        for (int i = primero + 1; i < productos.size(); i++) {
            Product p = productos.get(i);
            if (!sinStock(p, ahora)) quedan.add(p);
        }
        return quedan;
    }

    private boolean sinStock(Product p, Instant ahora) {
        return Boolean.FALSE.equals(p.enStock())
                && EstadoStock.de(p.enStock(), p.stockChequeadoAt(), ahora, vigencia) == EstadoStock.SIN_STOCK;
    }
}
