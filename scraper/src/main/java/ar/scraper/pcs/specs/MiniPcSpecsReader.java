package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads gama + nivel + chip brand off a mini PC's name — reusing {@link
 * CpuSpecsReader}'s scale verbatim (CODE-6, pc-builder-homelab T2): a mini
 * PC's CPU is named the same way a standalone CPU's is ("Ryzen 7 6800H",
 * "Core I5 10210U"), so it ranks on the same axes {@code EjesTecnicos.CPU}
 * already defines.
 *
 * <p>{@code capacidadGb} reads RAM, not storage: the FIRST standalone
 * "NNGb" token, same left-to-right convention {@link RamSpecsReader} uses
 * for a kit multiplier. This catalog states RAM before storage ("16Gb
 * 480Gb"), so the first match is the RAM.</p>
 */
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
