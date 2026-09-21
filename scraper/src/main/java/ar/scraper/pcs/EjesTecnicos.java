package ar.scraper.pcs;

import java.util.Comparator;

/**
 * Named technical-quality comparators, one per slot's non-price axis.
 * Wired into {@link CriterioPorEjesTecnicos}, which always appends precio
 * asc then url asc after whichever of these runs — price is a tiebreak
 * here, never the objective (pc-builder-gama T3b; replaces the ranking by
 * {@code baseMlScore}, a PRICE percentile, that picked the cheapest
 * candidate of any slot).
 *
 * <p>Every rank below puts the axis's abstention LAST, never first: a
 * candidate whose gama/tipo/DDR could not be read must never outrank one
 * that declares its technology. {@link Gama#DESCONOCIDA} and {@link
 * TipoAlmacenamiento#DESCONOCIDO} are mapped explicitly — never by their
 * enum ordinal, same reasoning as {@link Gama}'s own javadoc. {@code 0} in
 * {@code velocidadMhz}/{@code capacidadGb} and {@code ""} in {@code ddr}
 * are that same abstention sentinel for their axes.</p>
 */
public final class EjesTecnicos {

    private EjesTecnicos() {
    }

    /** DDR desc — la del nombre, y si no parseó, la derivada del socket. */
    public static final Comparator<TechSpecs> MOTHER =
            Comparator.comparingInt(specs -> ddrRank(ContextoDeArmado.derivarMotherDdr(specs)));

    public static final Comparator<TechSpecs> CPU =
            Comparator.comparingInt(specs -> gamaRank(specs.gama()));

    public static final Comparator<TechSpecs> RAM =
            Comparator.<TechSpecs>comparingInt(specs -> ddrRank(specs.ddr()))
                    .thenComparingInt(specs -> masEsMejor(specs.velocidadMhz()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    /** Sin ejes: un gabinete más grande no es "mejor" — sólo precio decide. */
    public static final Comparator<TechSpecs> GABINETE = (a, b) -> 0;

    /**
     * Sin ejes: no hay eje de tecnología medido para coolers (AIO vs aire,
     * altura, TDP) — trabajo pendiente que necesita datos, no código
     * (pc-builder-gama T3b-2).
     */
    public static final Comparator<TechSpecs> COOLER = (a, b) -> 0;

    public static final Comparator<TechSpecs> FUENTE =
            Comparator.comparingInt(specs -> -specs.certificacion().ordinal());

    public static final Comparator<TechSpecs> GPU =
            Comparator.comparingInt(specs -> gamaRank(specs.gama()));

    public static final Comparator<TechSpecs> ALMACENAMIENTO =
            Comparator.<TechSpecs>comparingInt(specs -> tipoAlmacenamientoRank(specs.tipoAlmacenamiento()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    // ── ranks: menor es mejor, la abstención siempre al final ───────────

    private static int ddrRank(String ddr) {
        return switch (ddr) {
            case "DDR5" -> 0;
            case "DDR4" -> 1;
            case "DDR3" -> 2;
            default -> Integer.MAX_VALUE; // "" — no parseó, ni siquiera derivada
        };
    }

    private static int gamaRank(Gama gama) {
        if (!gama.esConocida()) return Integer.MAX_VALUE;
        return switch (gama) {
            case ALTA -> 0;
            case MEDIA -> 1;
            case BAJA -> 2;
            case DESCONOCIDA -> Integer.MAX_VALUE; // inalcanzable: esConocida() ya lo filtró arriba
        };
    }

    private static int tipoAlmacenamientoRank(TipoAlmacenamiento tipo) {
        if (!tipo.esConocido()) return Integer.MAX_VALUE;
        return switch (tipo) {
            case NVME -> 0;
            case SSD -> 1;
            case HDD -> 2;
            case DESCONOCIDO -> Integer.MAX_VALUE; // inalcanzable, ver arriba
        };
    }

    /** "Más es mejor" para un entero >= 0, con 0 (abstención) siempre al final, nunca primero. */
    private static int masEsMejor(int valor) {
        return valor == 0 ? Integer.MAX_VALUE : -valor;
    }
}
