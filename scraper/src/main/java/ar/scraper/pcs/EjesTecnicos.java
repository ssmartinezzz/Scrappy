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

    /**
     * DDR desc → tier de chipset, en el orden absoluto de T3 (X/Z=1 < B=2 <
     * A/H=3, 0 última) — equivalente a {@link #mother(Gama)} sin gama
     * pedida. Ver {@link #mother(Gama)} para el ranking RELATIVO a la gama
     * (D9, pc-builder-deep-taxonomy T4c).
     */
    public static final Comparator<TechSpecs> MOTHER = mother(null);

    /**
     * DDR desc → tier de chipset relativo a {@code gamaPedida} — D9. Sin
     * gama pedida ({@code null}) o con {@link Gama#DESCONOCIDA}, no hay
     * target y se mantiene el orden absoluto de T3 (X/Z < B < A/H). Con una
     * gama pedible, el target es ALTA→X/Z, MEDIA→B, BAJA→A/H y el rank es la
     * distancia |tier - target| — 0 (abstención) sigue siempre última.
     */
    public static Comparator<TechSpecs> mother(Gama gamaPedida) {
        return Comparator.<TechSpecs>comparingInt(specs -> ddrRank(ContextoDeArmado.derivarMotherDdr(specs)))
                .thenComparingInt(specs -> tierChipsetRank(specs.tierChipset(), gamaPedida));
    }

    /** Gama → generación desc — D4. */
    public static final Comparator<TechSpecs> CPU =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(specs.generacion()));

    /** DDR desc → módulos (kit) desc → MHz desc → GB desc — D4: el kit va ANTES que la velocidad. */
    public static final Comparator<TechSpecs> RAM =
            Comparator.<TechSpecs>comparingInt(specs -> ddrRank(specs.ddr()))
                    .thenComparingInt(specs -> masEsMejor(specs.modulos()))
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

    /** Gama → generación desc → VRAM desc — D4. */
    public static final Comparator<TechSpecs> GPU =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(specs.generacion()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

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

    /**
     * D9: sin target (gamaPedida null o DESCONOCIDA) usa el tier tal cual
     * (ya es "menor es mejor": 1=X/Z, 2=B, 3=A/H, T3's absolute order). Con
     * target, el rank es la distancia |tier - target| — el chipset que más
     * se acerca a lo pedido gana, no el más alto en la escala absoluta. 0
     * (abstención) siempre último, en cualquiera de los dos modos.
     */
    private static int tierChipsetRank(int tier, Gama gamaPedida) {
        if (tier == 0) return Integer.MAX_VALUE;
        Integer target = targetTierChipset(gamaPedida);
        return target == null ? tier : Math.abs(tier - target);
    }

    private static Integer targetTierChipset(Gama gamaPedida) {
        if (gamaPedida == null) return null;
        return switch (gamaPedida) {
            case ALTA -> 1;
            case MEDIA -> 2;
            case BAJA -> 3;
            case DESCONOCIDA -> null; // gama pedida ilegible: sin target, orden absoluto de T3
        };
    }
}
