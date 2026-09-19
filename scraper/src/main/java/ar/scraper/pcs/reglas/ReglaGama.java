package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a cpu/gpu candidate whose gama isn't the one requested.
 *
 * D2 (pc-builder-gama): esta es la ÚNICA regla del sistema donde la
 * abstención VETA. Todas las demás reglas de esta carpeta dejan pasar
 * cuando alguno de los dos lados no parseó — acá es al revés A PROPÓSITO:
 * si el usuario pidió una gama, de un nombre que no se pudo leer NO SE
 * PUEDE AFIRMAR que esté en esa gama. El precio es que ~17% de las CPU
 * quedan fuera de cualquier armado con gama pedida (medido); el mensaje de
 * slot vacío lo explica (D6, T3b). No "arreglar" esto para que se parezca a
 * las demás reglas — la inconsistencia es intencional.
 */
public class ReglaGama implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        Gama pedida = contexto.gamaPedida();
        if (pedida == null) return true; // no se pidió gama: sin filtro
        return candidato.gama() == pedida;
    }

    @Override
    public String motivo() {
        return "ningún candidato alcanza la gama pedida";
    }
}
