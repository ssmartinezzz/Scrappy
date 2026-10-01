package ar.scraper.indices;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class Serie {

    private final Indice indice;
    private final List<PuntoIndice> puntos;

    public Serie(Indice indice, List<PuntoIndice> puntos) {
        this.indice = indice;
        this.puntos = puntos.stream().sorted(Comparator.comparing(PuntoIndice::fecha)).toList();
    }

    public static Serie vacia(Indice indice) {
        return new Serie(indice, List.of());
    }

    public Indice indice() {
        return indice;
    }

    public List<PuntoIndice> puntos() {
        return puntos;
    }

    public boolean estaVacia() {
        return puntos.isEmpty();
    }

    public Optional<PuntoIndice> primero() {
        return puntos.isEmpty() ? Optional.empty() : Optional.of(puntos.get(0));
    }

    public Optional<PuntoIndice> ultimo() {
        return puntos.isEmpty() ? Optional.empty() : Optional.of(puntos.get(puntos.size() - 1));
    }

    /**
     * Last observed point at or before {@code fecha}; empty when {@code fecha} precedes the first
     * point.
     */
    public Optional<PuntoIndice> valorEn(LocalDate fecha) {
        PuntoIndice mejor = null;
        for (PuntoIndice p : puntos) {
            if (p.fecha().isAfter(fecha)) break;
            mejor = p;
        }
        return Optional.ofNullable(mejor);
    }

    /**
     * . % change between the last point and the point {@code meses} months earlier, resolved by
     * DATE — never by how many points happen to sit in between (that conflation is the bug this
     * area replaces).
     */
    public Optional<Double> variacionHace(int meses) {
        if (puntos.size() < 2) return Optional.empty();
        PuntoIndice actual = ultimo().orElseThrow();
        Optional<PuntoIndice> anchor = indice.frecuencia() == Indice.Frecuencia.MENSUAL
                ? anchorPorMes(actual.fecha(), meses)
                : valorEn(actual.fecha().minusMonths(meses));
        return anchor
                .filter(p -> p.valor() > 0)
                .map(p -> (actual.valor() - p.valor()) / p.valor() * 100.0);
    }

    private Optional<PuntoIndice> anchorPorMes(LocalDate fecha, int meses) {
        YearMonth objetivo = YearMonth.from(fecha).minusMonths(meses);
        return puntos.stream()
                .filter(p -> !YearMonth.from(p.fecha()).isAfter(objetivo))
                .max(Comparator.comparing(PuntoIndice::fecha));
    }

    /** A bound outside the observed range is never silently clamped to a wrong number: */
    public Deflactor deflactor(LocalDate desde, LocalDate hasta) {
        if (estaVacia()) return Deflactor.NEUTRO;

        LocalDate primeraFecha = primero().orElseThrow().fecha();
        LocalDate ultimaFecha = ultimo().orElseThrow().fecha();

        boolean extrapoladoDesde = desde.isBefore(primeraFecha);
        boolean extrapoladoHasta = hasta.isAfter(ultimaFecha);

        double valorDesde = extrapoladoDesde
                ? primero().orElseThrow().valor()
                : valorEn(desde).orElseThrow().valor();
        double valorHasta = extrapoladoHasta
                ? Extrapolador.proyectar(this, hasta)
                : valorEn(hasta).orElseThrow().valor();

        Confianza confianza = (extrapoladoDesde || extrapoladoHasta) ? Confianza.EXTRAPOLADO : Confianza.OBSERVADO;
        int dias = 0;
        if (extrapoladoHasta) dias += (int) ChronoUnit.DAYS.between(ultimaFecha, hasta);
        if (extrapoladoDesde) dias += (int) ChronoUnit.DAYS.between(desde, primeraFecha);

        return new Deflactor(valorHasta / valorDesde, confianza, dias);
    }
}
