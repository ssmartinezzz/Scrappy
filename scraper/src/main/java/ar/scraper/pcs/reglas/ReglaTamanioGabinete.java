package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;
import lombok.RequiredArgsConstructor;

/**
 * {@code DESCONOCIDO} (abstención) vetoes when a size was requested — and here that is EXPENSIVE,
 * because 576 of 622 Gabinete rows abstain (measured 2026-09-22).
 */
@RequiredArgsConstructor
public class ReglaTamanioGabinete implements ReglaCompatibilidad {

    private final TamanioGabinete pedido;

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedido == null) return true;
        return candidato.tamanioGabinete() == pedido;
    }

    @Override
    public String motivo() {
        return "no es un gabinete " + pedido + ", el tamaño pedido";
    }
}
