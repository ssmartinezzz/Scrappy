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
 *
 * <p>{@code nivel} (D1, pc-builder-top-tier) va entre {@code gama} y {@code
 * generacion} en CPU y GPU, y es el único eje de potencia comparable ENTRE
 * marcas: {@code gama} mete a un i7 y a un i9 en la misma bolsa, y {@code
 * generacion} es la generación real en Intel pero el dígito de los miles del
 * modelo en AMD y Nvidia — {@code 14 > 9 > 5} hacía ganar al i7 sobre un
 * Ryzen 9 y a una RX 9070 sobre una RTX 5080, por aritmética y no por
 * potencia.</p>
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

    /**
     * Gama → nivel de familia desc → recencia desc — D1/D2.
     *
     * <p>En CPU el nivel va ANTES que el año, al revés que en {@link #GPU}:
     * el dígito de familia es un escalón estable y de vida larga (un i9 es el
     * tope de su generación, siempre), así que un i9 de 2023 vale más que un
     * Ryzen 7 de 2024.</p>
     */
    public static final Comparator<TechSpecs> CPU =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(specs.nivel()))
                    .thenComparingInt(specs -> masEsMejor(anioCpu(specs.marcaChip(), specs.generacion())));

    /** DDR desc → módulos (kit) desc → MHz desc → GB desc — D4: el kit va ANTES que la velocidad. */
    public static final Comparator<TechSpecs> RAM =
            Comparator.<TechSpecs>comparingInt(specs -> ddrRank(specs.ddr()))
                    .thenComparingInt(specs -> masEsMejor(specs.modulos()))
                    .thenComparingInt(specs -> masEsMejor(specs.velocidadMhz()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    /** Sin ejes: un gabinete más grande no es "mejor" — sólo precio decide. */
    public static final Comparator<TechSpecs> GABINETE = (a, b) -> 0;

    /**
     * LIQUIDO desc sobre AIRE — DESCONOCIDO siempre última (T4d-2,
     * pc-builder-deep-taxonomy). Antes de esto el slot no tenía eje (AIO vs
     * aire, altura, TDP siguen sin parsearse) y el precio más bajo de una
     * categoría con ruido de clasificación ganaba: build (4) de T4 eligió un
     * paño de limpieza para pasta térmica ($1.800) para el slot cooler.
     *
     * <p>La fase 9 (D6) le agrega el radiador como SEGUNDO eje: entre dos
     * AIO gana la de 360mm sobre la de 240mm. 84 de los 171 líquidos del
     * catálogo lo declaran; los otros 87 abstienen (0) y, por D7, quedan
     * detrás dentro de su propio escalón — nunca delante.</p>
     */
    public static final Comparator<TechSpecs> COOLER =
            Comparator.<TechSpecs>comparingInt(specs -> tipoCoolerRank(specs.tipoCooler()))
                    .thenComparingInt(specs -> masEsMejor(specs.radiadorMm()));

    /**
     * Certificación desc → watts desc — D6, fase 9.
     *
     * <p>Hasta la fase 8 el eje era la certificación SOLA, así que entre dos
     * GOLD desempataba el precio y ganaba siempre la más chica: la fuente
     * quedaba apenas por encima del piso de watts que {@link
     * EstimadorDeConsumo} pide, sin margen para nada. La certificación sigue
     * mandando —una GOLD de 650 W le gana a una sin certificar de 1200 W— y
     * el reparto por cuotas de la fase 8 impide que este eje le vacíe la caja
     * a los slots que vienen después.</p>
     */
    public static final Comparator<TechSpecs> FUENTE =
            Comparator.<TechSpecs>comparingInt(specs -> -specs.certificacion().ordinal())
                    .thenComparingInt(specs -> masEsMejor(specs.watts()));

    /**
     * Gama → recencia desc → nivel de modelo desc → VRAM desc — D1/D2.
     *
     * <p>En GPU el año va ANTES que el nivel, al revés que en {@link #CPU}:
     * el escalón de modelo no sobrevive a cinco años de proceso — una RX 6900
     * XT (x90 de 2020) no es comparable con una RTX 5080 (x80 de 2025), y con
     * el nivel primero le ganaba. Dentro del año el escalón sí decide.
     * {@code gama} corre antes que los dos, así que una x50 nueva nunca le
     * gana a una x90 vieja: están en gamas distintas.</p>
     */
    public static final Comparator<TechSpecs> GPU =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(anioGpu(specs.marcaChip(), specs.generacion())))
                    .thenComparingInt(specs -> masEsMejor(specs.nivel()))
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
            case "DDR2" -> 3;
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

    private static int tipoCoolerRank(TipoCooler tipo) {
        if (!tipo.esConocido()) return Integer.MAX_VALUE;
        return switch (tipo) {
            case LIQUIDO -> 0;
            case AIRE -> 1;
            case DESCONOCIDO -> Integer.MAX_VALUE; // inalcanzable, ver arriba
        };
    }

    /**
     * D2: {@code generacion} normalizada a año de lanzamiento, ramificando por
     * marca — sin eso no es una magnitud, son dos. En Intel es la generación
     * real ({@code 14} = Raptor Lake Refresh); en AMD es el dígito de los
     * miles del modelo ({@code 9} = serie 9000). Sin marca legible, o fuera de
     * tabla, abstiene ({@code 0}) y el eje lo manda al final (D13): un número
     * sin escala no puede rankear contra uno que sí la tiene.
     *
     * <p>La tabla es por SLOT, no global: {@code AMD} + {@code 9} es la serie
     * Ryzen 9000 (2024) en CPU y la RX 9000 (2025) en GPU. Un mapa único diría
     * que son lo mismo.</p>
     */
    private static int anioCpu(String marcaChip, int generacion) {
        if ("AMD".equals(marcaChip)) {
            return switch (generacion) { // el dígito de los miles del modelo Ryzen
                case 1 -> 2017; case 2 -> 2018; case 3 -> 2019;
                case 5 -> 2020; case 7 -> 2022; case 9 -> 2024;
                default -> 0;
            };
        }
        if ("INTEL".equals(marcaChip)) {
            return switch (generacion) { // la generación Core, con Ultra 200 mapeada a 15 por el reader
                case 8 -> 2017; case 9 -> 2018; case 10 -> 2020; case 11 -> 2021;
                case 12 -> 2021; case 13 -> 2022; case 14 -> 2023; case 15 -> 2024;
                default -> 0;
            };
        }
        return 0;
    }

    /** @see #anioCpu — misma idea, tabla propia: acá {@code AMD 9} es la RX 9000. */
    private static int anioGpu(String marcaChip, int generacion) {
        if ("NVIDIA".equals(marcaChip)) {
            return switch (generacion) { // 1 = GTX 10xx/16xx (el reader lee los miles)
                case 1 -> 2016; case 2 -> 2018; case 3 -> 2020; case 4 -> 2022; case 5 -> 2025;
                default -> 0;
            };
        }
        if ("AMD".equals(marcaChip)) {
            return switch (generacion) { // el dígito de los miles del modelo Radeon
                case 5 -> 2019; case 6 -> 2020; case 7 -> 2022; case 9 -> 2025;
                default -> 0;
            };
        }
        return 0;
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
