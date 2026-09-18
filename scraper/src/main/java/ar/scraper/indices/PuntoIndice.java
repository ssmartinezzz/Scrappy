package ar.scraper.indices;

import java.time.LocalDate;

/** One observed level of {@code indice} at {@code fecha}. */
public record PuntoIndice(Indice indice, LocalDate fecha, double valor) {}
