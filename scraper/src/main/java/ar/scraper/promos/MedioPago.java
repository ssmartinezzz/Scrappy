package ar.scraper.promos;

/** A canonical payment medium: the name stored in {@code medio_pago.nombre} and its kind. */
public record MedioPago(String nombre, TipoMedio tipo) {}
