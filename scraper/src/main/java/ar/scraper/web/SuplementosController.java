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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/** Supplement subtypes and the supplement builder. */
@RestController
@RequestMapping("/api")
public class SuplementosController {

    private final ScraperService service;
    private final OutfitService outfitService;

    public SuplementosController(ScraperService service, OutfitService outfitService) {
        this.service = service;
        this.outfitService = outfitService;
    }

    /**
     * Supplement subtypes in combo-assembly order. Pure taxonomy, so it answers before the first
     * scrape and the frontend selector no longer hard-codes the list. Goes straight to
     * {@link SupplementCombo}: it needs no instance state.
     */
    @GetMapping("/suplementos/tipos")
    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementoTipos>> suplementosTipos() {
        List<OutfitsDtos.Tipo> tipos = new ArrayList<>();
        for (var t : SupplementCombo.tiposDisponibles()) {
            tipos.add(new OutfitsDtos.Tipo(t.tipo(), t.grupo()));
        }
        return ResponseEntity.ok(ApiResponse.ok(new OutfitsDtos.SuplementoTipos(tipos)));
    }

    /**
     * One product per requested supplement type. {@code excluir} holds URLs already shown, so
     * "Regenerar" offers the next candidate. 204 when no scrape data exists, 400 when tipos is blank.
     */
    @GetMapping("/suplementos/builder")
    public ResponseEntity<ApiResponse<OutfitsDtos.SuplementosBuilder>> suplementosBuilder(@RequestParam(required = false) String tipos,
            @RequestParam(defaultValue = "0") double presupuesto,
            @RequestParam(defaultValue = "") String excluir) {
        if (StringUtils.isBlank(tipos)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "tipos is required");
        }

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> tiposSet = Arrays.stream(tipos.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        if (tiposSet.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "tipos is required");
        }

        Set<String> excluirUrls = StringUtils.isBlank(excluir)
                ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

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
