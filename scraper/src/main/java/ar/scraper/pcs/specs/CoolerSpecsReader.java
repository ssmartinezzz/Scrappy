package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;

/**
 * Cooler abstains entirely, same as phase 1 — 483 rows in catalog and no
 * trivial, measured signal to read off the name yet.
 */
public final class CoolerSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Cooler";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return TechSpecs.EMPTY;
    }
}
