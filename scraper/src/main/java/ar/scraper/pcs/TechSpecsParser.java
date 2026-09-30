package ar.scraper.pcs;

import ar.scraper.pcs.specs.AlmacenamientoSpecsReader;
import ar.scraper.pcs.specs.CoolerSpecsReader;
import ar.scraper.pcs.specs.CpuSpecsReader;
import ar.scraper.pcs.specs.FuenteSpecsReader;
import ar.scraper.pcs.specs.GabineteSpecsReader;
import ar.scraper.pcs.specs.GpuSpecsReader;
import ar.scraper.pcs.specs.LectorDeSpecs;
import ar.scraper.pcs.specs.MiniPcSpecsReader;
import ar.scraper.pcs.specs.MotherboardSpecsReader;
import ar.scraper.pcs.specs.RamSpecsReader;
import ar.scraper.pcs.specs.Tokens;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

/**
 * Pure and abstention-first — a field is only ever filled when its category's {@link LectorDeSpecs}
 * actually matched something.
 */
public final class TechSpecsParser {

    private TechSpecsParser() {}

    private static final Map<String, LectorDeSpecs> LECTORES = Stream.of(
                    new CpuSpecsReader(),
                    new MotherboardSpecsReader(),
                    new RamSpecsReader(),
                    new FuenteSpecsReader(),
                    new GabineteSpecsReader(),
                    new GpuSpecsReader(),
                    new CoolerSpecsReader(),
                    new AlmacenamientoSpecsReader(),
                    new MiniPcSpecsReader())
            .collect(Collectors.toMap(LectorDeSpecs::categoria, lector -> lector));

    public static TechSpecs parse(String nombre, String categoria) {
        if (StringUtils.isBlank(nombre) || categoria == null) return TechSpecs.EMPTY;

        LectorDeSpecs lector = LECTORES.get(categoria);
        if (lector == null) return TechSpecs.EMPTY;

        return lector.leer(Tokens.de(nombre));
    }
}
