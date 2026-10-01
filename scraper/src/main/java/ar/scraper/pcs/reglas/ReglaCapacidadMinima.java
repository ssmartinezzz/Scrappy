package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * {@code 0} (abstención) vetoes when a floor was requested — from a name whose capacity could not
 * be read, nothing can be asserted about whether it reaches the floor.
 */
public class ReglaCapacidadMinima implements ReglaCompatibilidad {

    private final Integer pedido;

    public ReglaCapacidadMinima(Integer pedido) {
        this.pedido = pedido;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedido == null) return true;
        return candidato.capacidadGb() >= pedido;
    }

    @Override
    public String motivo() {
        return "no llega a los " + pedido + " GB pedidos";
    }
}
