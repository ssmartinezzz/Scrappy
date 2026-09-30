package ar.scraper.pcs;

/**
 * {@code gamaPedida == null} ("no se pidió gama") and {@link Gama#BAJA} both resolve to the
 * pre-existing 450/650 with no certification requirement, so a caller that never asks for a gama
 * sees no change.
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
