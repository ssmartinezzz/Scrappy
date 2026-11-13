package ar.scraper.catalog;

import ar.scraper.model.Product;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
        return productos.stream().filter(p -> estado(p) != EstadoStock.SIN_STOCK).toList();
    }
}
