package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoCooler;
import lombok.RequiredArgsConstructor;

/**
 * In this category that is a feature rather than a cost — what abstains here is thermal paste,
 * cleaning cloths and case fans, which {@link ar.scraper.pcs.specs.CoolerSpecsReader} deliberately
 * does not read as coolers.
 */
@RequiredArgsConstructor
public class ReglaTipoCoolerPedido implements ReglaCompatibilidad {

    private final TipoCooler pedido;

    @Override
    public boolean permite(TechSpecs candidato, ContextoDeArmado contexto) {
        if (pedido == null) return true;
        return candidato.tipoCooler() == pedido;
    }

    @Override
    public String motivo() {
        return "no es refrigeración " + pedido + ", la pedida";
    }
}
