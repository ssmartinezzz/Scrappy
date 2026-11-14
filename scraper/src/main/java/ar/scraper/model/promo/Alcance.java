package ar.scraper.model.promo;

/** Mirrors the {@code promo_financiacion.alcance} CHECK. */
public enum Alcance {
    SITIO("sitio"),
    PRODUCTO("producto");

    private final String sql;

    Alcance(String sql) { this.sql = sql; }

    public String sql() { return sql; }
}
