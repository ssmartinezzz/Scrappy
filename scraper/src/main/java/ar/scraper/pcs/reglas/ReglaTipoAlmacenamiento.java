package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

/**
 * Vetoes an almacenamiento candidate whose {@code tipoAlmacenamiento()}
 * isn't the one requested. D2: {@code DESCONOCIDO} (abstention) vetoes when
 * a tipo was requested — {@link ar.scraper.pcs.PreferenciasDeArmado}'s
 * compact constructor already rejects {@code DESCONOCIDO} as a requested
 * value, so {@code pedido} is always a real technology when non-null.
 */
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
