package ar.scraper.model;

import java.util.List;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

@Builder(toBuilder = true)
public record Product(
        String sitio,
        String nombre,
        double precio,
        Double precioOriginal,
        String url,
        String imagenUrl,
        String categoria,
        String genero,
        List<String> talles,
        MlScore ml,
        String marca,
        String rubro,
        boolean gymrat,
        boolean marcaPremium,
        SenalCompra senal,
        SenalFinanciacion finan,
        int cantidadUnidades,  // unit count detected from nombre (pack/combo); 1 = single unit
        String subCategoria,
        VisualAttrs visual     // image-derived attributes (fit/estampado/escote/color); fill-only,
                               // additive PER FIELD — MlEnricher/DatabaseService only overwrite a
                               // field when the ML score/upsert value is non-blank, else the prior
                               // value is preserved (RELY-001; never wipe to "" on a run/backfill
                               // that didn't gate this product into image classification)
) implements Comparable<Product> {

    public static ProductBuilder builder() {
        return new ProductBuilder()
                .ml(MlScore.EMPTY).marca("").rubro("indumentaria")
                .senal(SenalCompra.EMPTY).finan(SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).subCategoria("").visual(VisualAttrs.EMPTY);
    }

    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, MlScore.EMPTY, "", "indumentaria", false, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, "", "indumentaria", false, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, "indumentaria", false, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro, boolean gymrat) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, false,
             SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }

    /**
     * Legacy 15-arg shape (the canonical constructor BEFORE {@code finan} was added as the 16th
     * component).
     */
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, SenalFinanciacion.EMPTY, 1, "");
    }

    /**
     * Legacy 16-arg shape (the canonical constructor BEFORE {@code cantidadUnidades} was added as
     * the 17th component).
     */
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal,
                   SenalFinanciacion finan) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, finan, 1, "");
    }

    /**
     * Legacy 17-arg shape (the canonical constructor BEFORE {@code subCategoria} was added as the
     * 18th component).
     */
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
                   String url, String imagenUrl, String categoria, String genero,
                   List<String> talles, MlScore ml, String marca, String rubro,
                   boolean gymrat, boolean marcaPremium, SenalCompra senal,
                   SenalFinanciacion finan, int cantidadUnidades) {
        this(sitio, nombre, precio, precioOriginal, url, imagenUrl,
             categoria, genero, talles, ml, marca, rubro, gymrat, marcaPremium,
             senal, finan, cantidadUnidades, "");
    }

    /**
     * Legacy 18-arg shape (the canonical constructor BEFORE {@code visual} was added as the 19th
     * component).
     */
    public Product(String sitio, String nombre, double precio, Double precioOriginal,
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
    public boolean tieneDescuento()  { return precioOriginal != null; }
    public boolean esTech()          { return "tecnologia".equals(rubro); }
    public boolean esGymrat()        { return gymrat; }
    public boolean esMarcaPremium()  { return marcaPremium; }
    public boolean esPack()          { return cantidadUnidades > 1; }

    public record MlScore(
            int          scoreP,
            List<String> badges,
            boolean      ofertaReal,
            String       tendencia,
            int          pctilCategoria,
            double       zScore,
            String       segment
    ) {
        public static final MlScore EMPTY =
            new MlScore(50, List.of(), false, "estable", 50, 0.0, "standard");

        public String badge() { return badges.isEmpty() ? "" : badges.get(0); }

        /**
         * Preserves source compatibility for call sites built before {@code badges} replaced the
         * single {@code badge} string component; a non-blank badge becomes a one-element list.
         */
        public MlScore(int scoreP, String badge, boolean ofertaReal,
                       String tendencia, int pctilCategoria, double zScore, String segment) {
            this(scoreP, StringUtils.isNotBlank(badge) ? List.of(badge) : List.of(),
                 ofertaReal, tendencia, pctilCategoria, zScore, segment);
        }

        public MlScore(int scoreP, String badge, boolean ofertaReal,
                       String tendencia, int pctilCategoria) {
            this(scoreP, badge, ofertaReal, tendencia, pctilCategoria, 0.0, "standard");
        }
    }

    public record SenalCompra(
            String senal,
            int    scoreCompra,
            ar.scraper.indices.Confianza confianzaDeflactor
    ) {
        public static final SenalCompra EMPTY =
                new SenalCompra("sin_datos", 50, ar.scraper.indices.Confianza.SIN_DATOS);

        public SenalCompra conConfianza(ar.scraper.indices.Confianza confianza) {
            return new SenalCompra(senal, scoreCompra, confianza);
        }
    }

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
     * All values are Spanish labels from a closed set, or {@code ""} when the model abstains (low
     * confidence) or image classification was unavailable/skipped — text classification is never
     * overridden by these fields.
     */
    public record VisualAttrs(
            String fit,
            String estampado,
            String escote,
            String colorDominante
    ) {
        public static final VisualAttrs EMPTY = new VisualAttrs("", "", "", "");
    }
}
