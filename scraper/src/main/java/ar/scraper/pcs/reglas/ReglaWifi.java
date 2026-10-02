package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import lombok.RequiredArgsConstructor;

/**
 * Vetoes a motherboard candidate that doesn't declare wifi, but only when TRUE was requested —
 * FALSE/null never filter.
 */
@RequiredArgsConstructor
public class ReglaWifi implements ReglaCompatibilidad {

    private final Boolean pedido;

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (!Boolean.TRUE.equals(pedido)) return true;
        return candidato.wifi();
    }

    @Override
    public String motivo() {
        return "la motherboard no dice wifi";
    }
}
