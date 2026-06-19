package ar.scraper.model;

import java.util.List;

public record Product(
        String sitio,
        String nombre,
        double precio,
        String precioOriginal,
        String url,
        String imagenUrl,
        String categoria,
        String genero,
        List<String> talles,
        MlScore ml,
        String marca,
        String rubro,         // "indumentaria" | "tecnologia" | "suplementos"
        boolean gymrat,       // tag transversal aditivo (no altera categoria/rubro)
        boolean marcaPremium, // tag transversal aditivo (no altera categoria/rubro/badge)
        SenalCompra senal     // precomputed buy-signal (mirrors MlScore precompute pattern)
) implements Comparable<Product> {

    // ── Constructors legacy (retrocompatibles) ──────────────────────────────
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, MlScore.EMPTY, "", "indumentaria", false, false, SenalCompra.EMPTY);
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, "", "indumentaria", false, false, SenalCompra.EMPTY);
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, "indumentaria", false, false, SenalCompra.EMPTY);
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro, boolean gymrat) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, false, SenalCompra.EMPTY);
    }

    @Override
    public int compareTo(Product o) { return Double.compare(this.precio, o.precio); }
    public String precioFormateado() { return String.format("%,.0f", precio); }
    public boolean tieneDescuento()  { return precioOriginal != null && !precioOriginal.isBlank(); }
    public boolean esTech()          { return "tecnologia".equals(rubro); }
    public boolean esGymrat()        { return gymrat; }
    public boolean esMarcaPremium()  { return marcaPremium; }

    public record MlScore(
            int     scoreP,
            String  badge,
            boolean ofertaReal,
            String  tendencia,
            int     pctilCategoria,
            double  zScore,
            String  segment
    ) {
        public static final MlScore EMPTY =
            new MlScore(50, "", false, "estable", 50, 0.0, "standard");
        public MlScore(int scoreP, String badge, boolean ofertaReal,
                       String tendencia, int pctilCategoria) {
            this(scoreP, badge, ofertaReal, tendencia, pctilCategoria, 0.0, "standard");
        }
    }

    /**
     * Precomputed buy-signal classification (mirrors {@link MlScore}'s
     * precompute-at-scrape-time pattern). Produced by
     * {@code ar.scraper.ml.SenalCalculator}, the same classification logic
     * previously inline in {@code ApiController.recomendacion}.
     */
    public record SenalCompra(
            String senal,
            int    scoreCompra
    ) {
        public static final SenalCompra EMPTY = new SenalCompra("sin_datos", 50);
    }
}
