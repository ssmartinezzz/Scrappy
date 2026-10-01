package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

/**
 * Todas las demás reglas de esta carpeta dejan pasar cuando alguno de los dos lados no parseó — acá
 * es al revés A PROPÓSITO: si el usuario pidió una gama, de un nombre que no se pudo leer NO SE
 * PUEDE AFIRMAR que esté en esa gama.
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
