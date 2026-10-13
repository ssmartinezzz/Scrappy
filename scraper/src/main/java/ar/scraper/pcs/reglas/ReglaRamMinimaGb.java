package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import lombok.RequiredArgsConstructor;

/**
 * Floor on the kit total ({@code 2x16GB} is 32). {@code 0} (abstención) vetoes when a floor was
 * requested, same as {@link ReglaCapacidadMinima}.
 */
@RequiredArgsConstructor
public class ReglaRamMinimaGb implements ReglaCompatibilidad {

    private final Integer pedido;

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedido == null) return true;
        return candidato.capacidadGb() >= pedido;
    }

    @Override
    public String motivo() {
        return "no llega a los " + pedido + " GB de RAM pedidos";
    }
}
