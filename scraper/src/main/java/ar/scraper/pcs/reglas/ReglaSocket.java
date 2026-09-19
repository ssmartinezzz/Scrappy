package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

public class ReglaSocket implements ReglaCompatibilidad {

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        String cpuSocket = candidato.socket();
        String motherSocket = contexto.motherSpecs().socket();
        boolean veta = !cpuSocket.isEmpty() && !motherSocket.isEmpty() && !cpuSocket.equals(motherSocket);
        return !veta;
    }

    @Override
    public String motivo() {
        return "el socket de la CPU no coincide con el de la motherboard";
    }
}
