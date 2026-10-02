package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;

import java.util.List;

public final class MiniPcSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Mini PC";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, tokens.gbSuelto().orElse(0), "",
                CpuSpecsReader.gama(tokens), Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(),
                CpuSpecsReader.marcaChip(tokens), 0, 0, 0, false, TipoCooler.DESCONOCIDO,
                CpuSpecsReader.nivel(tokens));
    }
}
