package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

public class ReglaDdr implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        String ramDdr = candidato.ddr();
        String motherDdr = contexto.motherDdr();
        boolean veta = !ramDdr.isEmpty() && !motherDdr.isEmpty() && !ramDdr.equals(motherDdr);
        return !veta;
    }

    @Override
    public String motivo() {
        return "la generación de RAM no coincide con la de la motherboard";
    }
}
