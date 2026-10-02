package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReglaDdrPedidaRam implements ReglaCompatibilidad {

    private final String pedida;

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
