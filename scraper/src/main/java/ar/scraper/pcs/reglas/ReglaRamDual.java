package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;

/**
 * Vetoes a ram candidate that isn't a dual (2x) kit, but only when TRUE was
 * requested — FALSE/null never filter (D1: only TRUE asks for something).
 * {@code modulos()==0} is abstention and vetoes when asked, same as every
 * other D2 axis; the D2 exception documented on {@link ReglaWifi} is about
 * an explicit FALSE assertion, which doesn't apply here.
 */
public class ReglaRamDual implements ReglaCompatibilidad {

    private final Boolean pedido;

    public ReglaRamDual(Boolean pedido) {
        this.pedido = pedido;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (!Boolean.TRUE.equals(pedido)) return true;
        return candidato.modulos() >= 2;
    }

    @Override
    public String motivo() {
        return "no es un kit dual (2x)";
    }
}
