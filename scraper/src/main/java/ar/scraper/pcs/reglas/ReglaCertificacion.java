package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a Fuente that doesn't reach the minimum 80 PLUS certification the
 * requested gama demands. {@code Certificacion.NINGUNA} follows the house's
 * NORMAL abstention policy — it does NOT veto, unlike {@link ReglaGama}'s D2
 * inversion — because 52 of 347 fuentes in the catalog don't declare a
 * certification, and vetoing them would empty the slot in builds where the
 * source is otherwise fine.
 */
public class ReglaCertificacion implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (candidato.certificacion() == Certificacion.NINGUNA) return true;
        return candidato.certificacion().alcanza(contexto.certificacionMinima());
    }

    @Override
    public String motivo() {
        return "la fuente no alcanza la certificación mínima requerida";
    }
}
