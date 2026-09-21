package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads supported sockets off a cooler's name (T2d, pc-builder-deep-
 * taxonomy). Everything else abstains, same as phase 1: 483 rows in catalog
 * and no other trivial, measured signal to read off the name yet.
 *
 * <p>A bare "intel"/"amd" word names no socket by itself (D6: both sides of
 * a rule must parse) — only an explicit socket token counts.</p>
 */
public final class CoolerSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Cooler";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, 0, "",
                ar.scraper.pcs.Gama.DESCONOCIDA, ar.scraper.pcs.Certificacion.NINGUNA,
                0, ar.scraper.pcs.TipoAlmacenamiento.DESCONOCIDO, socketsSoportados(tokens));
    }

    private static List<String> socketsSoportados(Tokens tokens) {
        List<String> sockets = new ArrayList<>();
        if (tokens.has("am4")) sockets.add("AM4");
        if (tokens.has("am5")) sockets.add("AM5");
        if (tokens.has("am3")) sockets.add("AM3");
        if (tokens.has("lga1700") || tokens.has("1700")) sockets.add("LGA1700");
        if (tokens.has("lga1851") || tokens.has("1851")) sockets.add("LGA1851");
        if (tokens.has("lga1200") || tokens.has("1200")) sockets.add("LGA1200");
        // "115x" is the compact catalog form and maps ONLY to LGA1151, never LGA1200.
        if (tokens.has("lga1151") || tokens.has("1151") || tokens.has("115x")) sockets.add("LGA1151");
        return List.copyOf(sockets);
    }
}
