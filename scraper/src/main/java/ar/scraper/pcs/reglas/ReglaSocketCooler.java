package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/** Cooler ↔ motherboard socket (D6, pc-builder-deep-taxonomy T2d). */
public class ReglaSocketCooler implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        String motherSocket = contexto.motherSpecs().socket();
        if (candidato.socketsSoportados().isEmpty() || motherSocket.isEmpty()) return true;
        return candidato.socketsSoportados().contains(motherSocket);
    }

    @Override
    public String motivo() {
        return "el cooler no soporta el socket de la motherboard";
    }
}
