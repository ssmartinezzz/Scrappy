package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a ram candidate whose own {@code ddr()} isn't the one requested.
 * D2: abstention vetoes when a DDR was requested.
 */
public class ReglaDdrPedidaRam implements ReglaCompatibilidad {

    private final String pedida;

    public ReglaDdrPedidaRam(String pedida) {
        this.pedida = pedida;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedida == null) return true;
        return pedida.equals(candidato.ddr());
    }

    @Override
    public String motivo() {
        return "no es " + pedida + ", la DDR pedida";
    }
}
