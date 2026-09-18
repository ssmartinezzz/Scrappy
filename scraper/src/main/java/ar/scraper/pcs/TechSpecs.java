package ar.scraper.pcs;

/**
 * Hardware-compatibility attributes read off a PC part's name. Mirrors
 * {@code Product.VisualAttrs}'s abstention policy: every field is fill-only,
 * {@code ""}/{@code 0} means "the parser didn't assert this", never "no
 * socket"/"no watts". A category only fills the fields it can actually read
 * off the name — see {@link TechSpecsParser} for which.
 */
public record TechSpecs(
        String socket,       // "AM4" | "AM5" | "LGA1700" | "LGA1851" | ""
        String ddr,          // "DDR3" | "DDR4" | "DDR5" | ""
        String formFactor,   // "ITX" | "MATX" | "ATX" | "EATX" | ""
        int watts,
        int capacidadGb,
        String tipoMemoria   // "DIMM" | "SODIMM" | ""
) {
    public static final TechSpecs EMPTY = new TechSpecs("", "", "", 0, 0, "");
}
