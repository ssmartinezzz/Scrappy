package ar.scraper.pcs;

import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Antes de esto {@code PcBuilder} era greedy: cada slot veía TODO el restante y, como el precio es
 * sólo desempate, se llevaba el mejor candidato que entrara.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
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

    public static CuotasDePresupuesto para(List<SlotDeArmado> slots) {
        return para(slots, Uso.GAMING);
    }

    /**
     * Un slot fuera de la tabla toma la share media de los que sí están, en vez de cero: un slot
     * nuevo que nadie agregó acá debe recibir algo de plata, no quedar condenado al fallback del
     * más barato en todo armado con presupuesto.
     */
    public static CuotasDePresupuesto para(List<SlotDeArmado> slots, Uso uso) {
        Map<String, Double> shares = uso == Uso.HOMELAB ? SHARES_HOMELAB : SHARES_GAMING;
        double media = shares.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double total = slots.stream().mapToDouble(s -> shares.getOrDefault(s.nombre(), media)).sum();
        if (total <= 0) return new CuotasDePresupuesto(Map.of());
        return new CuotasDePresupuesto(slots.stream().collect(java.util.stream.Collectors.toMap(
                SlotDeArmado::nombre, s -> shares.getOrDefault(s.nombre(), media) / total, (a, b) -> a)));
    }

    public double cuota(String slot, double presupuesto) {
        return normalizadas.getOrDefault(slot, 0.0) * presupuesto;
    }
}
