package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a motherboard candidate whose own DDR (declared, or derived from
 * its socket — {@link ContextoDeArmado#derivarMotherDdr}) isn't the one
 * requested. D2 (pc-builder-deep-taxonomy): abstention vetoes when a DDR
 * was requested — a board whose DDR can't be read nor derived (e.g.
 * LGA1700) is indistinguishable from one that doesn't match.
 */
public class ReglaDdrPedidaMother implements ReglaCompatibilidad {

    private final String pedida;

    public ReglaDdrPedidaMother(String pedida) {
        this.pedida = pedida;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedida == null) return true;
        return pedida.equals(ContextoDeArmado.derivarMotherDdr(candidato));
    }

    @Override
    public String motivo() {
        return "no es " + pedida + ", la DDR pedida";
    }
}
