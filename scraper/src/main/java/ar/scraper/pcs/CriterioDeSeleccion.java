package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.List;

/** Picks one product among a slot's compatible, affordable candidates. */
public interface CriterioDeSeleccion {

    Product elegir(List<Product> candidatos);
}
