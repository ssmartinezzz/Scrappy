package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes an almacenamiento candidate below the requested capacity floor
 * (fase 9, D3). It is a FLOOR, not an exact match: asking for 1 TB must not
 * drop the 2 TB, which is the better candidate and which the ranking
 * (capacidad desc) would have picked anyway.
 *
 * <p>D2: {@code 0} (abstención) vetoes when a floor was requested — from a
 * name whose capacity could not be read, nothing can be asserted about
 * whether it reaches the floor. Measured: 297/297 Almacenamiento rows do
 * declare it, so this costs almost nothing in practice (2026-09-22).</p>
 */
public class ReglaCapacidadMinima implements ReglaCompatibilidad {

    private final Integer pedido;

    public ReglaCapacidadMinima(Integer pedido) {
        this.pedido = pedido;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedido == null) return true;
        return candidato.capacidadGb() >= pedido;
    }

    @Override
    public String motivo() {
        return "no llega a los " + pedido + " GB pedidos";
    }
}
