package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a ram candidate that isn't a dual (2x) kit, but only when TRUE was requested — FALSE/null
 * never filter.
 */
public class ReglaRamDual implements ReglaCompatibilidad {

    private final Boolean pedido;

    public ReglaRamDual(Boolean pedido) {
        this.pedido = pedido;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (!Boolean.TRUE.equals(pedido)) return true;
        return candidato.modulos() >= 2;
    }

    @Override
    public String motivo() {
        return "no es un kit dual (2x)";
    }
}
