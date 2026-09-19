package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * One compatibility check on a single candidate's specs against what the
 * build already decided. A rule only vetoes when it can actually assert
 * something about both sides — abstention (either side didn't parse) always
 * permits, never vetoes.
 */
public interface ReglaCompatibilidad {

    boolean permite(TechSpecs candidato, ContextoDeArmado contexto);

    /** Why this rule would veto a candidate — unused until T3's empty-slot messages. */
    String motivo();
}
