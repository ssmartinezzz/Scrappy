package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.List;

/**
 * {@code contexto} is what the build already decided — e.g. the requested gama, which the
 * motherboard's chipset-tier ranking needs to rank RELATIVE to, not every criterio reads it.
 */
public interface CriterioDeSeleccion {

    Product elegir(List<Product> candidatos, ContextoDeArmado contexto);
}
