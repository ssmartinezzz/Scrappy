package ar.scraper.model.promo;

import java.util.Arrays;
import java.util.Optional;

/** Mirrors the {@code medio_pago.tipo} CHECK. */
public enum TipoMedio {
    BANCO("banco"),
    BILLETERA("billetera"),
    TARJETA("tarjeta"),
    PASARELA("pasarela"),
    MEDIO("medio");

    private final String sql;

    TipoMedio(String sql) { this.sql = sql; }

    public String sql() { return sql; }

    public static Optional<TipoMedio> deSql(String s) {
        return Arrays.stream(values()).filter(t -> t.sql.equals(s)).findFirst();
    }
}
