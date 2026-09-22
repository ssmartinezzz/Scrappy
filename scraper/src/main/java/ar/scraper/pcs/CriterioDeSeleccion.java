package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.List;

/**
 * Picks one product among a slot's compatible, affordable candidates.
 * {@code contexto} is what the build already decided (D9,
 * pc-builder-deep-taxonomy T4c) — e.g. the requested gama, which the
 * motherboard's chipset-tier ranking needs to rank RELATIVE to, not every
 * criterio reads it.
 */
public interface CriterioDeSeleccion {

    Product elegir(List<Product> candidatos, ContextoDeArmado contexto);
}
