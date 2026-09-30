package ar.scraper.indices;

import java.time.LocalDate;

public record PuntoIndice(Indice indice, LocalDate fecha, double valor) {}
