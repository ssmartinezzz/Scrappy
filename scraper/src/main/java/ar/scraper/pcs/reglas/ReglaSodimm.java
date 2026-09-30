package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/** SODIMM is notebook memory. */
public class ReglaSodimm implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        return !"SODIMM".equals(candidato.tipoMemoria());
    }

    @Override
    public String motivo() {
        return "memoria SODIMM (notebook), no sirve en una PC de escritorio";
    }
}
