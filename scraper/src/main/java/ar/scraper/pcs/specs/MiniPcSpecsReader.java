package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;


public final class MiniPcSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Mini PC";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return TechSpecs.builder()
                .capacidadGb(tokens.gbSuelto().orElse(0))
                .gama(CpuSpecsReader.gama(tokens))
                .marcaChip(CpuSpecsReader.marcaChip(tokens))
                .nivel(CpuSpecsReader.nivel(tokens))
                .build();
    }
}
