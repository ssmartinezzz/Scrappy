package ar.scraper.pcs;

/** One assembled slot's pick. {@code specs} is computed at build time, never stored on {@code Product}. */
public record PcPick(
        String slot, String sitio, String nombre, double precio,
        String url, String img, String marca, TechSpecs specs) {
}
