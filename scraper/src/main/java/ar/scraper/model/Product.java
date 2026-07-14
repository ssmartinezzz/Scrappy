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
        SenalCompra senal,    // precomputed buy-signal (mirrors MlScore precompute pattern)
        SenalFinanciacion finan, // precomputed financing signal (independent from senal/scoreCompra)
        int cantidadUnidades,  // unit count detected from nombre (pack/combo); 1 = single unit
        String subCategoria,   // activity/sport-based sub-dimension; "" when none resolved
        VisualAttrs visual     // image-derived attributes (fit/estampado/escote/color); fill-only,
                               // additive PER FIELD — MlEnricher/DatabaseService only overwrite a
                               // field when the ML score/upsert value is non-blank, else the prior
                               // value is preserved (RELY-001; never wipe to "" on a run/backfill
                               // that didn't gate this product into image classification)
) implements Comparable<Product> {

    // ── Constructors legacy (retrocompatibles) ──────────────────────────────
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, MlScore.EMPTY, "", "indumentaria", false, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, "", "indumentaria", false, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, "indumentaria", false, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro, boolean gymrat) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }

    /**
     * Legacy 15-arg shape (the canonical constructor BEFORE {@code finan} was
     * added as the 16th component). Preserves source compatibility for the
     * ~12 call sites that build a {@code Product} up to {@code senal} only;
     * defaults the new financing signal to {@link SenalFinanciacion#EMPTY}
     * and {@code cantidadUnidades} to 1 (single unit).
     */
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, SenalFinanciacion.EMPTY, 1, "");
    }

    /**
     * Legacy 16-arg shape (the canonical constructor BEFORE
     * {@code cantidadUnidades} was added as the 17th component). Preserves
     * source compatibility for call sites built against the {@code finan}
     * tail; defaults {@code cantidadUnidades} to 1 (single unit).
     */
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal,
                   SenalFinanciacion finan) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, finan, 1, "");
    }

    /**
     * Legacy 17-arg shape (the canonical constructor BEFORE
     * {@code subCategoria} was added as the 18th component). Preserves
     * source compatibility for call sites built against the
     * {@code cantidadUnidades} tail; defaults {@code subCategoria} to
     * {@code ""} (no activity sub-dimension resolved).
     */
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal,
                   SenalFinanciacion finan, int cantidadUnidades) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, finan, cantidadUnidades, "");
    }

    /**
     * Legacy 18-arg shape (the canonical constructor BEFORE {@code visual}
     * was added as the 19th component). Preserves source compatibility for
     * call sites built against the {@code subCategoria} tail; defaults
     * {@code visual} to {@link VisualAttrs#EMPTY} (no image-derived
     * attributes resolved — text-only classification unaffected).
     */
    public Product(String sitio, String nombre, double precio, String precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal,
                   SenalFinanciacion finan, int cantidadUnidades, String subCategoria) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, finan, cantidadUnidades, subCategoria, VisualAttrs.EMPTY);
    }

    @Override
    public int compareTo(Product o) { return Double.compare(this.precio, o.precio); }
    public String precioFormateado() { return String.format("%,.0f", precio); }
    public boolean tieneDescuento()  { return precioOriginal != null && !precioOriginal.isBlank(); }
    public boolean esTech()          { return "tecnologia".equals(rubro); }
    public boolean esGymrat()        { return gymrat; }
    public boolean esMarcaPremium()  { return marcaPremium; }
    public boolean esPack()          { return cantidadUnidades > 1; }

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

    /**
     * Precomputed financing signal ("¿conviene en cuotas?") — fully
     * independent from {@link SenalCompra}/{@code scoreCompra}. Produced by
     * {@code ar.scraper.ml.FinanciacionCalculator} from the active
     * financing preset's surcharge/installment count and the current
     * monthly inflation rate.
     */
    public record SenalFinanciacion(
            String senal,
            double ahorroReal,
            double vp,
            double cuota,
            int    cuotas,
            double recargoPct
    ) {
        public static final SenalFinanciacion EMPTY =
            new SenalFinanciacion("sin_datos", 0, 0, 0, 0, 0);
    }

    /**
     * Image-derived visual attributes (mirrors {@link MlScore}'s
     * precompute-at-scrape-time pattern). Produced by the Marqo-FashionSigLIP
     * zero-shot classification pipeline ({@code ml_embeddings.py}) and
     * applied by {@code ar.scraper.ml.MlEnricher}. All values are Spanish
     * labels from a closed set, or {@code ""} when the model abstains
     * (low confidence) or image classification was unavailable/skipped —
     * text classification is never overridden by these fields.
     */
    public record VisualAttrs(
            String fit,            // "oversize" | "entallado" | "regular" | ""
            String estampado,      // "estampado" | "liso" | ""
            String escote,         // "cuello redondo" | "en v" | "capucha" | "con cuello" | ""
            String colorDominante  // fixed Spanish palette (e.g. "azul", "rojo") | ""
    ) {
        public static final VisualAttrs EMPTY = new VisualAttrs("", "", "", "");
    }
}
