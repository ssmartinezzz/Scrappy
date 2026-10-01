package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * A rule only vetoes when it can actually assert something about both sides — abstention (either
 * side didn't parse) always permits, never vetoes.
 */
public interface ReglaCompatibilidad {

    boolean permite(TechSpecs candidato, ContextoDeArmado contexto);

    String motivo();
}
