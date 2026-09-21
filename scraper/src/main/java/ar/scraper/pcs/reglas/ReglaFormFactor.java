package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

import java.util.List;

public class ReglaFormFactor implements ReglaCompatibilidad {

    private static final List<String> ORDEN_FORM_FACTOR = List.of("ITX", "MATX", "ATX", "EATX");

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        String gabineteFormFactor = candidato.formFactor();
        String motherFormFactor = contexto.motherSpecs().formFactor();
        if (gabineteFormFactor.isEmpty() || motherFormFactor.isEmpty()) return true;
        boolean veta = ORDEN_FORM_FACTOR.indexOf(gabineteFormFactor) < ORDEN_FORM_FACTOR.indexOf(motherFormFactor);
        return !veta;
    }

    @Override
    public String motivo() {
        return "el gabinete es más chico que la motherboard";
    }
}
