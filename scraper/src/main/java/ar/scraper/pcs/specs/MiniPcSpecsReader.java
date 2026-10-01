package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MiniPcSpecsReader implements LectorDeSpecs {

    private static final Pattern GB_STANDALONE = Pattern.compile("^(\\d+)gb$");

    @Override
    public String categoria() {
        return "Mini PC";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, capacidadGb(tokens), "",
                CpuSpecsReader.gama(tokens), Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(),
                CpuSpecsReader.marcaChip(tokens), 0, 0, 0, false, TipoCooler.DESCONOCIDO,
                CpuSpecsReader.nivel(tokens));
    }

    private static int capacidadGb(Tokens tokens) {
        for (String t : tokens.array()) {
            Matcher m = GB_STANDALONE.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}
