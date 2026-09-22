package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a gabinete whose tower size isn't the one requested (fase 9, D1).
 * Independent from {@link ReglaFormFactor}, which keeps running on {@code
 * formFactor}: the tower size is how much desk it takes, the form factor is
 * which board fits inside.
 *
 * <p>D2: {@code DESCONOCIDO} (abstención) vetoes when a size was requested —
 * and here that is EXPENSIVE, because 576 of 622 Gabinete rows abstain
 * (measured 2026-09-22). It is the price of "mid-tower" meaning something;
 * without a request the rule never fires and the pool is untouched.</p>
 */
public class ReglaTamanioGabinete implements ReglaCompatibilidad {

    private final TamanioGabinete pedido;

    public ReglaTamanioGabinete(TamanioGabinete pedido) {
        this.pedido = pedido;
    }

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
