package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a motherboard candidate that doesn't declare wifi, but only when
 * TRUE was requested — FALSE/null never filter (D1: only TRUE asks for
 * something). D2's exception: {@code wifi()==false} is an ASSERTION ("the
 * name doesn't say wifi"), never an abstention, so there is no third state
 * to distinguish here — the veto is a plain mismatch, not the abstention
 * inversion {@link ar.scraper.pcs.reglas.ReglaGama} documents.
 */
public class ReglaWifi implements ReglaCompatibilidad {

    private final Boolean pedido;

    public ReglaWifi(Boolean pedido) {
        this.pedido = pedido;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (!Boolean.TRUE.equals(pedido)) return true;
        return candidato.wifi();
    }

    @Override
    public String motivo() {
        return "la motherboard no dice wifi";
    }
}
