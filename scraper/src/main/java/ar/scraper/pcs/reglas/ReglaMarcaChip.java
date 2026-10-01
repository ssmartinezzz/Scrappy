package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

public class ReglaMarcaChip implements ReglaCompatibilidad {

    private final String marcaPedida;

    public ReglaMarcaChip(String marcaPedida) {
        this.marcaPedida = marcaPedida;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (marcaPedida == null) return true;
        return marcaPedida.equals(candidato.marcaChip());
    }

    @Override
    public String motivo() {
        return "no es " + marcaPedida + ", la marca pedida";
    }
}
