package ar.scraper.pcs;

/**
 * Watts floor + minimum 80 PLUS certification for a build, keyed by the
 * requested gama. Replaces {@code PcBuilder}'s {@code WATTS_MIN_SIN_GPU=450}
 * / {@code WATTS_MIN_CON_GPU=650} constants with a table that also covers
 * MEDIA/ALTA (pc-builder-gama T3a).
 *
 * These numbers are ASSUMED, not measured — same as the 450/650 they
 * replace (see CLAUDE.md, "El piso de watts es una constante supuesta, no
 * medida"). {@code gamaPedida == null} ("no se pidió gama") and {@link
 * Gama#BAJA} both resolve to the pre-existing 450/650 with no certification
 * requirement, so a caller that never asks for a gama sees no change.
 */
public final class EstimadorDeConsumo {

    private EstimadorDeConsumo() {
    }

    public static int wattsMinimos(Gama gamaPedida, boolean conGpu) {
        if (gamaPedida == Gama.ALTA) return conGpu ? 1000 : 750;
        if (gamaPedida == Gama.MEDIA) return conGpu ? 750 : 550;
        return conGpu ? 650 : 450; // null (sin pedido), BAJA, o DESCONOCIDA
    }

    public static Certificacion certificacionMinima(Gama gamaPedida) {
        if (gamaPedida == Gama.ALTA) return Certificacion.GOLD;
        if (gamaPedida == Gama.MEDIA) return Certificacion.BRONZE;
        return Certificacion.NINGUNA; // null (sin pedido), BAJA, o DESCONOCIDA
    }
}
