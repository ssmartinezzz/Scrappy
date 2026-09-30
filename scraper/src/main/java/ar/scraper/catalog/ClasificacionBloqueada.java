package ar.scraper.catalog;

/** Read-side value object for the manual classification lock. */
public record ClasificacionBloqueada(
        String categoria,
        String subCategoria,
        String marca,
        String genero,
        String rubro
) {
}
