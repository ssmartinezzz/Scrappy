package ar.scraper.model.promo;

/** Mirrors the {@code promo_financiacion.tipo} CHECK. */
public enum TipoPromo {
    CUOTAS_SIN_INTERES("cuotas_sin_interes"),
    DESCUENTO("descuento"),
    RECARGO("recargo");

    private final String sql;

    TipoPromo(String sql) { this.sql = sql; }

    public String sql() { return sql; }
}
