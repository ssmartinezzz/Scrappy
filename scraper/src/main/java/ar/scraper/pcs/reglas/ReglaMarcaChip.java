package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a candidate whose {@code marcaChip()} isn't the one requested — the
 * same rule wires into mother, cpu and gpu slots, constructed once per slot
 * with the preference axis that slot reads (D3: {@code marcaCpu} for mother
 * and cpu — a mother's marcaChip is already derived from socket by
 * {@code MotherboardSpecsReader}, T3b — {@code marcaGpu} for gpu). D2:
 * abstention vetoes when a marca was requested.
 */
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
