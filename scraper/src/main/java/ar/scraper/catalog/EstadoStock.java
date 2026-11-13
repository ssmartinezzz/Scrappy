package ar.scraper.catalog;

import java.time.Duration;
import java.time.Instant;

/** Derived stock state of a product row. Owner of the rule; nobody re-derives it from the raw columns. */
public enum EstadoStock {
    EN_STOCK("en_stock"), SIN_STOCK("sin_stock"), DESCONOCIDO("desconocido");

    private final String wire;

    EstadoStock(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    public static EstadoStock de(Boolean enStock, Instant stockChequeadoAt, Instant ahora, Duration vigencia) {
        if (enStock == null || stockChequeadoAt == null) return DESCONOCIDO;
        // A check older than the vigencia says nothing about today: stale reads as unknown, never as in stock.
        if (stockChequeadoAt.isBefore(ahora.minus(vigencia))) return DESCONOCIDO;
        return enStock ? EN_STOCK : SIN_STOCK;
    }
}
