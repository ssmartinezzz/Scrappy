package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;

import java.util.List;

public final class GabineteSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Gabinete";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", tokens.formFactorExplicito(), 0, 0, "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA, 0, TipoAlmacenamiento.DESCONOCIDO,
                List.of(), "", 0, 0, 0, false, TipoCooler.DESCONOCIDO, 0,
                tamanio(tokens), 0);
    }

    private static TamanioGabinete tamanio(Tokens tokens) {
        String[] arr = tokens.array();
        for (int i = 0; i < arr.length - 1; i++) {
            if (!arr[i + 1].equals("tower")) continue;
            switch (arr[i]) {
                case "mini", "micro" -> { return TamanioGabinete.MINI; }
                case "mid", "medium" -> { return TamanioGabinete.MID; }
                case "full" -> { return TamanioGabinete.FULL; }
                default -> { }
            }
        }
        return TamanioGabinete.DESCONOCIDO;
    }
}
