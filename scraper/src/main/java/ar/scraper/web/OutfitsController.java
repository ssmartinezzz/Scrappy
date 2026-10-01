package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.outfits.FeedbackModels;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.SupplementCombo;
import ar.scraper.security.Sujeto;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.OpResult;
import ar.scraper.web.dto.OutfitsDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

@RestController
@RequestMapping("/api")
public class OutfitsController {

    private final ScraperService service;
    private final ar.scraper.feedback.FeedbackPort feedback;
    private final ar.scraper.outfits.SavedOutfitsPort outfitsGuardados;
    private final OutfitService outfitService;
    private final ar.scraper.security.ActorResolver actorResolver;

    public OutfitsController(ScraperService service,
                     ar.scraper.feedback.FeedbackPort feedback,
                     ar.scraper.outfits.SavedOutfitsPort outfitsGuardados,
                     OutfitService outfitService,
                     ar.scraper.security.ActorResolver actorResolver) {
        this.service = service;
        this.feedback = feedback;
        this.outfitsGuardados = outfitsGuardados;
        this.outfitService = outfitService;
        this.actorResolver = actorResolver;
    }

    private String safe(String s) { return s != null ? s : ""; }

    @GetMapping("/outfits")
    public ResponseEntity<ApiResponse<OutfitsDtos.Outfit>> outfits(@RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(defaultValue = "0") double presupuestoSuplementos) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> excluirUrls = excluir.isBlank() ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        java.util.UUID sujeto = Sujeto.de(actorResolver);
        var feedbackRows = feedback.obtenerOutfitFeedback(sujeto);
        var dismissCats  = feedback.obtenerCategoriaDismiss(sujeto);
        // Gym surface: gym feedback + shared feed signal ("catalog"), never casual.
        var feedback = FeedbackModels.build(feedbackRows, r.productos(), dismissCats, Set.of("gym", "catalog"));

        OutfitService.Outfit outfit = outfitService.armar(r.productos(), genero, "gym", feedback,
                presupuesto, excluirUrls);

        List<OutfitsDtos.SlotPick> slots = new ArrayList<>();
        for (var pick : outfit.slots()) {
            slots.add(slotPick(pick));
        }

        // Types are explicit: without them the combo uses ALL subtypes, so every new food category
        // would silently add a card to this grid. Here the stack is a fixed suggestion; choosing is
        // /suplementos' job.
        var suplementosList = outfitService.armarComboSuplementos(
                r.productos(), presupuestoSuplementos, SupplementCombo.TIPOS_COMBO_OUTFIT);
        double totalSuplementos = suplementosList.stream()
                .mapToDouble(OutfitService.SupplementPick::precio).sum();

        return ResponseEntity.ok(ApiResponse.ok(new OutfitsDtos.Outfit(
                outfit.genero(), outfit.partial(), outfit.totalEstimado(), outfit.presupuestoExcedido(),
                slots, totalSuplementos, SuplementoPicks.desde(suplementosList))));
    }

    private OutfitsDtos.SlotPick slotPick(OutfitService.SlotPick pick) {
        return new OutfitsDtos.SlotPick(pick.slot(), safe(pick.sitio()), safe(pick.nombre()),
                pick.precio(), safe(pick.url()), safe(pick.img()), safe(pick.categoria()), safe(pick.marca()));
    }

    /** No-fit is NOT an error: 200 with {@code noCumplePresupuesto:true} and empty slots. */
    @GetMapping("/outfits/builder")
    public ResponseEntity<ApiResponse<OutfitsDtos.Builder>> outfitsBuilder(@RequestParam(required = false) String categorias,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(required = false, defaultValue = "") String pin,
            @RequestParam(defaultValue = "false") boolean greedy,
            @RequestParam(required = false, defaultValue = "gym") String estilo) {
        // Only {gym, casual} are builder surfaces; anything else (blank, "null", the reserved feed
        // bucket "catalog") falls back to "gym".
        estilo = "casual".equalsIgnoreCase(estilo) ? "casual" : "gym";

        if (StringUtils.isBlank(categorias)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "Missing required parameter: categorias");
        }
        if (presupuesto <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "presupuesto must be a positive number");
        }

        List<String> catList = Arrays.stream(categorias.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .filter(OutfitService.KNOWN_CATEGORIAS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (catList.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "No valid categories provided. Use canonical category names.");
        }

        if (catList.size() > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "Too many categories (max 20 allowed)");
        }

        Set<String> excluirUrls = StringUtils.isBlank(excluir)
                ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        List<String> pinUrls = StringUtils.isBlank(pin)
                ? List.of()
                : Arrays.stream(pin.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList());

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        java.util.UUID sujeto = Sujeto.de(actorResolver);
        var feedbackRows = feedback.obtenerOutfitFeedback(sujeto);
        var dismissCats  = feedback.obtenerCategoriaDismiss(sujeto);
        var feedback     = FeedbackModels.build(feedbackRows, r.productos(), dismissCats,
                Set.of(estilo, "catalog"));

        // Unresolved pin URLs are silently dropped. One index instead of a catalog scan per pinned
        // URL (6700 products, every regen click); putIfAbsent keeps first-wins.
        List<Product> pinned = List.of();
        if (!pinUrls.isEmpty()) {
            Map<String, Product> porUrl = new HashMap<>();
            for (Product p : r.productos()) {
                if (p.url() != null) porUrl.putIfAbsent(p.url(), p);
            }
            pinned = pinUrls.stream()
                    .map(porUrl::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                r.productos(), catList, presupuesto, genero, feedback, excluirUrls, greedy, pinned, estilo);

        String status;
        if (result.slots().isEmpty()) {
            status = "no-fit";
        } else if (!result.categoriasVacias().isEmpty()) {
            status = "partial";
        } else {
            status = "ok";
        }

        List<OutfitsDtos.SlotPick> slots = new ArrayList<>();
        for (var pick : result.slots()) {
            slots.add(slotPick(pick));
        }
        boolean noFit = "no-fit".equals(status);
        return ResponseEntity.ok(ApiResponse.ok(new OutfitsDtos.Builder(
                status, slots, safe(result.genero()), result.presupuesto(), result.totalEstimado(),
                result.noCumplePresupuesto(), result.categoriasVacias(), result.categoriasSinPresupuesto(),
                noFit ? "No valid combination fits within the budget." : null,
                noFit ? result.minimoBudgetNecesario() : null)));
    }

    @PostMapping("/outfits/feedback")
    public ResponseEntity<ApiResponse<OpResult>> outfitFeedback(@RequestBody Map<String, Object> body) {
        String genero = String.valueOf(body.getOrDefault("genero", ""));
        String estilo = String.valueOf(body.getOrDefault("estilo", "gym"));
        if (estilo.isBlank() || "null".equals(estilo)) estilo = "gym";

        Object itemsObj = body.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) {
                    Object slot  = m.get("slot");
                    Object url   = m.get("url");
                    Object liked = m.get("liked");
                    if (slot == null || url == null || liked == null) continue; // silent skip
                    boolean likedBool = Boolean.parseBoolean(String.valueOf(liked));
                    feedback.guardarOutfitFeedbackItem(Sujeto.de(actorResolver), genero,
                            String.valueOf(slot), String.valueOf(url), likedBool, estilo);
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    @PostMapping("/outfits/save")
    public ResponseEntity<ApiResponse<OutfitsDtos.Guardado>> saveOutfit(@RequestBody Map<String, Object> body) {
        String nombre = String.valueOf(body.getOrDefault("nombre", "Outfit")).trim();
        Object slotsObj = body.get("slots");
        Object suplObj  = body.get("suplementos");
        double totalEstimado;
        try {
            totalEstimado = body.containsKey("totalEstimado")
                    ? Double.parseDouble(String.valueOf(body.get("totalEstimado"))) : 0.0;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "totalEstimado inválido");
        }
        String slotsJson;
        String suplJson;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            slotsJson = mapper.writeValueAsString(slotsObj != null ? slotsObj : List.of());
            suplJson  = suplObj != null ? mapper.writeValueAsString(suplObj) : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "slots o suplementos inválidos");
        }
        int id = outfitsGuardados.guardarOutfit(Sujeto.de(actorResolver), nombre, slotsJson, suplJson, totalEstimado);
        if (id < 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error_interno", "No se pudo guardar el outfit");
        }
        return ResponseEntity.ok(ApiResponse.ok(new OutfitsDtos.Guardado(true, id, nombre, totalEstimado)));
    }

    @GetMapping("/outfits/saved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSavedOutfits() {
        return ResponseEntity.ok(ApiResponse.ok(outfitsGuardados.obtenerOutfitsGuardados(Sujeto.de(actorResolver))));
    }

    @DeleteMapping("/outfits/saved/{id}")
    public ResponseEntity<ApiResponse<OpResult>> deleteSavedOutfit(@PathVariable int id) {
        // 404 covers "does not exist" AND "belongs to somebody else": telling them apart would
        // confirm another user's row exists.
        if (!outfitsGuardados.eliminarOutfitGuardado(Sujeto.de(actorResolver), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "Outfit no encontrado");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Outfit eliminado")));
    }

    @PatchMapping("/outfits/saved/{id}/nombre")
    public ResponseEntity<ApiResponse<OpResult>> renameSavedOutfit(@PathVariable int id,
            @RequestBody Map<String, Object> body) {
        String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
        if (nombre.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "nombre es obligatorio");
        }
        if (!outfitsGuardados.renombrarOutfit(Sujeto.de(actorResolver), id, nombre)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "Outfit no encontrado");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Outfit renombrado")));
    }

    @DeleteMapping("/outfits/feedback")
    public ResponseEntity<ApiResponse<OpResult>> resetOutfitFeedback(@RequestParam(required = false, defaultValue = "gym") String estilo) {
        feedback.limpiarOutfitFeedback(Sujeto.de(actorResolver), StringUtils.isBlank(estilo) ? "gym" : estilo);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Historial de feedback reseteado")));
    }
}
