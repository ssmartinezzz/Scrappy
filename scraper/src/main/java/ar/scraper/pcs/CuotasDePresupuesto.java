package ar.scraper.pcs;

import java.util.List;
import java.util.Map;

/**
 * Reparte el presupuesto entre los slots de un armado (D3,
 * odd/tasks/pc-builder-top-tier.md).
 *
 * <p>Antes de esto {@code PcBuilder} era greedy: cada slot veía TODO el
 * restante y, como el precio es sólo desempate, se llevaba el mejor candidato
 * que entrara. Medido sobre el catálogo real con $2.000.000, la RAM se llevaba
 * $1.102.200 —el 55% de la caja— y cuando llegaba el turno de la fuente no
 * quedaba nada asequible: caía al fallback "gastá lo mínimo" y elegía la más
 * barata del catálogo, sin certificar. El ranking de fuente ya era correcto;
 * nunca llegaba a ejercerse.</p>
 *
 * <p>Las proporciones son SUPUESTAS, no medidas — igual que el piso de watts de
 * {@link EstimadorDeConsumo}. Se normalizan sobre los slots realmente presentes,
 * así que no hace falta una tabla por combinación: sin GPU su 30% se reparte
 * proporcionalmente entre los demás, y el cooler (sólo gama ALTA) entra con el
 * suyo cuando aparece.</p>
 */
public final class CuotasDePresupuesto {

    private static final Map<String, Double> SHARES_GAMING = Map.of(
            "mother", 0.12,
            "cpu", 0.20,
            "cooler", 0.06,
            "ram", 0.10,
            "gabinete", 0.06,
            "fuente", 0.10,
            "gpu", 0.30,
            "almacenamiento", 0.12);

    /**
     * D5, pc-builder-homelab — supuestas, no medidas, igual que las de
     * arriba: mother 12 · cpu 18 · ram 18 · gabinete 6 · fuente 8 · sistema 8
     * · datos 20 · gpu 20. Un homelab gasta más en RAM y en el disco de
     * datos, menos en el disco de sistema que en {@code SHARES_GAMING}.
     */
    private static final Map<String, Double> SHARES_HOMELAB = Map.of(
            "mother", 0.12,
            "cpu", 0.18,
            "ram", 0.18,
            "gabinete", 0.06,
            "fuente", 0.08,
            "sistema", 0.08,
            "datos", 0.20,
            "gpu", 0.20);

    private final Map<String, Double> normalizadas;

    private CuotasDePresupuesto(Map<String, Double> normalizadas) {
        this.normalizadas = normalizadas;
    }

    /**
     * Pre-{@code pc-builder-homelab} shape: sin {@link Uso}, GAMING —
     * refactor contract, CODE-2.
     */
    public static CuotasDePresupuesto para(List<SlotDeArmado> slots) {
        return para(slots, Uso.GAMING);
    }

    /**
     * Un slot fuera de la tabla toma la share media de los que sí están, en vez
     * de cero: un slot nuevo que nadie agregó acá debe recibir algo de plata,
     * no quedar condenado al fallback del más barato en todo armado con
     * presupuesto. Cada {@link Uso} tiene su propia tabla (D5).
     */
    public static CuotasDePresupuesto para(List<SlotDeArmado> slots, Uso uso) {
        Map<String, Double> shares = uso == Uso.HOMELAB ? SHARES_HOMELAB : SHARES_GAMING;
        double media = shares.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double total = slots.stream().mapToDouble(s -> shares.getOrDefault(s.nombre(), media)).sum();
        if (total <= 0) return new CuotasDePresupuesto(Map.of());
        return new CuotasDePresupuesto(slots.stream().collect(java.util.stream.Collectors.toMap(
                SlotDeArmado::nombre, s -> shares.getOrDefault(s.nombre(), media) / total, (a, b) -> a)));
    }

    /** La parte del presupuesto que le toca a este slot, antes del arrastre. */
    public double cuota(String slot, double presupuesto) {
        return normalizadas.getOrDefault(slot, 0.0) * presupuesto;
    }
}
