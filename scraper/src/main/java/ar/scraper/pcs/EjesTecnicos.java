package ar.scraper.pcs;

import java.util.Comparator;

/**
 * Wired into {@link CriterioPorEjesTecnicos}, which always appends precio asc then url asc after
 * whichever of these runs — price is a tiebreak here, never the objective.
 */
public final class EjesTecnicos {

    private EjesTecnicos() {
    }

    public static final Comparator<TechSpecs> MOTHER = mother(null);

    public static Comparator<TechSpecs> mother(Gama gamaPedida) {
        return Comparator.<TechSpecs>comparingInt(specs -> ddrRank(ContextoDeArmado.derivarMotherDdr(specs)))
                .thenComparingInt(specs -> tierChipsetRank(specs.tierChipset(), gamaPedida));
    }

    public static final Comparator<TechSpecs> CPU =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(specs.nivel()))
                    .thenComparingInt(specs -> masEsMejor(anioCpu(specs.marcaChip(), specs.generacion())));

    public static final Comparator<TechSpecs> RAM =
            Comparator.<TechSpecs>comparingInt(specs -> ddrRank(specs.ddr()))
                    .thenComparingInt(specs -> masEsMejor(specs.modulos()))
                    .thenComparingInt(specs -> masEsMejor(specs.velocidadMhz()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    /** Sin ejes: un gabinete más grande no es "mejor" — sólo precio decide. */
    public static final Comparator<TechSpecs> GABINETE = (a, b) -> 0;

    public static final Comparator<TechSpecs> COOLER =
            Comparator.<TechSpecs>comparingInt(specs -> tipoCoolerRank(specs.tipoCooler()))
                    .thenComparingInt(specs -> masEsMejor(specs.radiadorMm()))
                    .thenComparingInt(specs -> claseDisipadorRank(specs.claseDisipador()))
                    .thenComparingInt(specs -> masEsMejor(specs.heatpipes()));

    public static final Comparator<TechSpecs> FUENTE =
            Comparator.<TechSpecs>comparingInt(specs -> -specs.certificacion().ordinal())
                    .thenComparingInt(specs -> masEsMejor(specs.watts()));

    /**
     * En GPU el año va ANTES que el nivel, al revés que en {@link #CPU}: el escalón de modelo no
     * sobrevive a cinco años de proceso — una RX 6900 XT (x90 de 2020) no es comparable con una RTX
     * 5080 (x80 de 2025), y con el nivel primero le ganaba.
     */
    public static final Comparator<TechSpecs> GPU =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(anioGpu(specs.marcaChip(), specs.generacion())))
                    .thenComparingInt(specs -> masEsMejor(specs.nivel()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    public static final Comparator<TechSpecs> ALMACENAMIENTO =
            Comparator.<TechSpecs>comparingInt(specs -> tipoAlmacenamientoRank(specs.tipoAlmacenamiento()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    public static final Comparator<TechSpecs> RAM_HOMELAB =
            Comparator.<TechSpecs>comparingInt(specs -> masEsMejor(specs.capacidadGb()))
                    .thenComparing(RAM);

    /**
     * El slot {@code datos} no es el disco de sistema: es el volumen a granel, y ahí GB/$ gana — al
     * revés que {@link #ALMACENAMIENTO}, que prioriza tecnología porque arma el disco de arranque.
     */
    public static final Comparator<TechSpecs> ALMACENAMIENTO_DATOS =
            Comparator.<TechSpecs>comparingInt(specs -> tecnologiaConocidaRank(specs.tipoAlmacenamiento()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()))
                    .thenComparingInt(specs -> tipoAlmacenamientoRankDatos(specs.tipoAlmacenamiento()));

    /**
     * Mismo molde que {@link #CPU} pero sin año/generación: un mini PC barebone no siempre declara
     * el año del chip, y lo que de verdad distingue dos mini PCs del mismo nivel es cuánta RAM
     * trae.
     */
    public static final Comparator<TechSpecs> MINI_PC =
            Comparator.<TechSpecs>comparingInt(specs -> gamaRank(specs.gama()))
                    .thenComparingInt(specs -> masEsMejor(specs.nivel()))
                    .thenComparingInt(specs -> masEsMejor(specs.capacidadGb()));

    private static int ddrRank(String ddr) {
        return switch (ddr) {
            case "DDR5" -> 0;
            case "DDR4" -> 1;
            case "DDR3" -> 2;
            case "DDR2" -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

    private static int gamaRank(Gama gama) {
        if (!gama.esConocida()) return Integer.MAX_VALUE;
        return switch (gama) {
            case ALTA -> 0;
            case MEDIA -> 1;
            case BAJA -> 2;
            case DESCONOCIDA -> Integer.MAX_VALUE;
        };
    }

    private static int tipoAlmacenamientoRank(TipoAlmacenamiento tipo) {
        if (!tipo.esConocido()) return Integer.MAX_VALUE;
        return switch (tipo) {
            case NVME -> 0;
            case SSD -> 1;
            case HDD -> 2;
            case DESCONOCIDO -> Integer.MAX_VALUE;
        };
    }

    private static int tecnologiaConocidaRank(TipoAlmacenamiento tipo) {
        return tipo.esConocido() ? 0 : 1;
    }

    private static int tipoAlmacenamientoRankDatos(TipoAlmacenamiento tipo) {
        if (!tipo.esConocido()) return Integer.MAX_VALUE;
        return switch (tipo) {
            case HDD -> 0;
            case SSD -> 1;
            case NVME -> 2;
            case DESCONOCIDO -> Integer.MAX_VALUE;
        };
    }

    private static int tipoCoolerRank(TipoCooler tipo) {
        if (!tipo.esConocido()) return Integer.MAX_VALUE;
        return switch (tipo) {
            case LIQUIDO -> 0;
            case AIRE -> 1;
            case DESCONOCIDO -> Integer.MAX_VALUE;
        };
    }

    private static int claseDisipadorRank(ClaseDisipador clase) {
        if (!clase.esConocida()) return Integer.MAX_VALUE;
        return switch (clase) {
            case DOBLE_TORRE -> 0;
            case TORRE -> 1;
            case DESCONOCIDA -> Integer.MAX_VALUE;
        };
    }

    /**
     * {@code generacion} normalizada a año de lanzamiento, ramificando por marca — sin eso no es
     * una magnitud, son dos.
     */
    private static int anioCpu(String marcaChip, int generacion) {
        if ("AMD".equals(marcaChip)) {
            return switch (generacion) {
                case 1 -> 2017; case 2 -> 2018; case 3 -> 2019;
                case 5 -> 2020; case 7 -> 2022; case 9 -> 2024;
                default -> 0;
            };
        }
        if ("INTEL".equals(marcaChip)) {
            return switch (generacion) {
                case 8 -> 2017; case 9 -> 2018; case 10 -> 2020; case 11 -> 2021;
                case 12 -> 2021; case 13 -> 2022; case 14 -> 2023; case 15 -> 2024;
                default -> 0;
            };
        }
        return 0;
    }

    private static int anioGpu(String marcaChip, int generacion) {
        if ("NVIDIA".equals(marcaChip)) {
            return switch (generacion) {
                case 1 -> 2016; case 2 -> 2018; case 3 -> 2020; case 4 -> 2022; case 5 -> 2025;
                default -> 0;
            };
        }
        if ("AMD".equals(marcaChip)) {
            return switch (generacion) {
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
            case DESCONOCIDA -> null;
        };
    }
}
