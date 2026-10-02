package ar.scraper.pcs.specs;

import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;


public final class GabineteSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Gabinete";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return TechSpecs.builder()
                .formFactor(tokens.formFactorExplicito())
                .tamanioGabinete(tamanio(tokens))
                .build();
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
