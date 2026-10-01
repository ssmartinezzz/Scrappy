package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;

public interface LectorDeSpecs {
    String categoria();
    TechSpecs leer(Tokens tokens);
}
