package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * SODIMM is notebook memory. Unlike every other rule here, {@code
 * tipoMemoria} is never an abstention (D6, pc-builder-deep-taxonomy) — {@link
 * ar.scraper.pcs.specs.RamSpecsReader} always asserts "SODIMM" or "DIMM" — so
 * this vetoes unconditionally, with no both-sides-parsed guard.
 */
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
