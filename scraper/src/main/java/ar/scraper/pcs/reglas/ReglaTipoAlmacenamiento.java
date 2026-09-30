package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

public class ReglaTipoAlmacenamiento implements ReglaCompatibilidad {

    private final TipoAlmacenamiento pedido;

    public ReglaTipoAlmacenamiento(TipoAlmacenamiento pedido) {
        this.pedido = pedido;
    }

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedido == null) return true;
        return candidato.tipoAlmacenamiento() == pedido;
    }

    @Override
    public String motivo() {
        return "no es " + pedido + ", el tipo de almacenamiento pedido";
    }
}
