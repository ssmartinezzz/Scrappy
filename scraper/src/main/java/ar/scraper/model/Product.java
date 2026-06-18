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
        boolean gymrat        // tag transversal aditivo (no altera categoria/rubro)
) implements Comparable<Product> {

    // ── Constructors legacy (retrocompatibles) ──────────────────────────────
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, MlScore.EMPTY, "", "indumentaria", false);
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, "", "indumentaria", false);
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, "indumentaria", false);
    }

    @Override
    public int compareTo(Product o) { return Double.compare(this.precio, o.precio); }
    public String precioFormateado() { return String.format("%,.0f", precio); }
    public boolean tieneDescuento()  { return precioOriginal != null && !precioOriginal.isBlank(); }
    public boolean esTech()          { return "tecnologia".equals(rubro); }
    public boolean esGymrat()        { return gymrat; }

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
}
