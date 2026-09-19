package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

public class ReglaWatts implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        int fuenteWatts = candidato.watts();
        int minimo = contexto.wattsMin();
        boolean veta = fuenteWatts != 0 && fuenteWatts < minimo;
        return !veta;
    }

    @Override
    public String motivo() {
        return "la fuente no alcanza el piso de watts requerido";
    }
}
