package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.SupplementCombo;
import ar.scraper.web.dto.OutfitsDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SuplementosController {

    private final ScraperService service;
    private final OutfitService outfitService;

    /** Supplement subtypes in combo-assembly order. */
    @GetMapping("/suplementos/tipos")
    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementoTipos>> suplementosTipos() {
        List<OutfitsDtos.Tipo> tipos = new ArrayList<>();
        for (var t : SupplementCombo.tiposDisponibles()) {
            tipos.add(new OutfitsDtos.Tipo(t.tipo(), t.grupo()));
        }
        return ResponseEntity.ok(ApiResponse.ok(new OutfitsDtos.SuplementoTipos(tipos)));
    }

    @GetMapping("/suplementos/builder")
    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementosBuilder>> suplementosBuilder(@RequestParam(required = false) String tipos,
            @RequestParam(defaultValue = "0") double presupuesto,
            @RequestParam(defaultValue = "") String excluir) {
        if (StringUtils.isBlank(tipos)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "tipos is required");
        }

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> tiposSet = Params.tokens(tipos).collect(Collectors.toSet());

        if (tiposSet.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "tipos is required");
        }

        Set<String> excluirUrls = Params.setOrEmpty(excluir);

        List<OutfitService.SupplementPick> picks =
                outfitService.armarComboSuplementos(r.productos(), presupuesto, tiposSet, excluirUrls);

        Set<String> foundTipos = picks.stream()
                .map(OutfitService.SupplementPick::tipo)
                .collect(Collectors.toSet());
        List<String> sinStock = tiposSet.stream()
                .filter(t -> !foundTipos.contains(t))
                .sorted()
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.ok(new OutfitsDtos.SuplementosBuilder(SuplementoPicks.desde(picks), sinStock)));
    }
}
