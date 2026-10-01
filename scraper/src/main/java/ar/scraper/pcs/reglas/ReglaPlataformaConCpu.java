package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Mother-only: vetoes a board whose platform (socket) has no CPU candidate in the pool matching the
 * requested gama/marcaCpu.
 */
public class ReglaPlataformaConCpu implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        var elegibles = contexto.socketsConCpuElegible();
        if (elegibles.isEmpty() || candidato.socket().isEmpty()) return true;
        return elegibles.contains(candidato.socket());
    }

    @Override
    public String motivo() {
        return "ningún CPU del pool alcanza esta plataforma para la gama pedida";
    }
}
