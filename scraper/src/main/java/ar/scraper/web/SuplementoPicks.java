package ar.scraper.web;

import ar.scraper.outfits.OutfitService;
import ar.scraper.web.dto.OutfitsDtos;

import java.util.ArrayList;
import java.util.List;

final class SuplementoPicks {

    private SuplementoPicks() {}

    static List<OutfitsDtos.SuplementoPick> desde(List<OutfitService.SupplementPick> picks) {
        List<OutfitsDtos.SuplementoPick> out = new ArrayList<>();
        for (var pick : picks) {
            out.add(new OutfitsDtos.SuplementoPick(pick.tipo(), seguro(pick.sitio()), seguro(pick.nombre()),
                    pick.precio(), seguro(pick.url()), seguro(pick.img()), seguro(pick.marca())));
        }
        return out;
    }

    private static String seguro(String s) { return s != null ? s : ""; }
}
