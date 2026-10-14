package ar.scraper.pcs;

import org.apache.commons.lang3.StringUtils;

/** Blank/null is the default, {@link ModoPresupuesto#AHORRAR}, like {@link UsoWire}. */
public final class ModoPresupuestoWire {

    private ModoPresupuestoWire() {}

    public static ModoPresupuesto parse(String valorWire) {
        if (StringUtils.isBlank(valorWire)) return ModoPresupuesto.AHORRAR;
        return switch (valorWire.trim().toLowerCase()) {
            case "ahorrar" -> ModoPresupuesto.AHORRAR;
            case "gastar" -> ModoPresupuesto.GASTAR_TODO;
            default -> throw new IllegalArgumentException("modo inválido: " + valorWire);
        };
    }
}
