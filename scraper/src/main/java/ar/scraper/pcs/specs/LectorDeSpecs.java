package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;

/** Reads the {@link TechSpecs} a single category's name can assert. */
public interface LectorDeSpecs {
    String categoria();
    TechSpecs leer(Tokens tokens);
}
