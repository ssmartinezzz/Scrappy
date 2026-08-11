package ar.scraper.web;

import ar.scraper.model.Product;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.Comparator;

/**
 * Armador de outfits (Gym): combina productos del catálogo agregado en
 * memoria (no consulta la DB — sigue el mismo patrón que /api/data,
 * /api/mejores, /api/marcas-browser) en torso + piernas + calzado,
 * con un accesorio opcional best-effort.
 *
 * No persiste outfits generados (stateless por request); el feedback
 * (like/dislike) se persiste aparte en DatabaseService.outfit_feedback_item y SÍ
 * influye en el muestreo desde outfit-recommendation-quality: dislike excluye
 * el par marca|categoria de forma permanente, like aumenta su peso de muestreo
 * (ver FeedbackModel, ADR-1/ADR-2 en design.md).
 */
@Service
public class OutfitService {

    private final RecommendationService recommendationService;

    /**
     * Supplement-combo bodies, extracted to their own class (backlog A3).
     * Built here rather than injected so this constructor's shape stays
     * unchanged for the existing test call sites — it takes the same
     * RecommendationService this class already receives, for the score tiebreak
     * in its pick ranking.
     */
    private final SupplementCombo supplementCombo;

    /**
     * Budget-builder bodies, extracted to their own class (backlog A3).
     * Built here rather than injected so this constructor's shape stays
     * unchanged for the existing test call sites.
     */
    private final OutfitBudgetBuilder budgetBuilder;

    public OutfitService(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
        this.budgetBuilder = new OutfitBudgetBuilder(recommendationService);
        this.supplementCombo = new SupplementCombo(recommendationService);
    }

    /** Slots requeridos para un outfit completo. */
    public static final String SLOT_TORSO     = "torso";
    public static final String SLOT_PIERNAS   = "piernas";
    public static final String SLOT_CALZADO   = "calzado";
    public static final String SLOT_ACCESORIO = "accesorio";

    private static final List<String> SLOTS_REQUERIDOS =
            List.of(SLOT_TORSO, SLOT_PIERNAS, SLOT_CALZADO);

    // Banda de precio: ±30% alrededor de la mediana del pool elegible.
    // Elegido como compromiso entre coherencia visual/económica del outfit
    // y disponibilidad de candidatos en catálogos chicos (ver tasks.md 1.4).
    private static final double PRICE_BAND_PCT = 0.30;

    // Boost de feedback (ADR-2 en design.md de outfit-recommendation-quality):
    // cada like sobre un par marca|categoria suma FEEDBACK_BOOST_STEP al multiplicador
    // de peso en weightedRandomPick, hasta un máximo de FEEDBACK_BOOST_CAP likes contados
    // (boostFactor ∈ [1.0, 1 + CAP*STEP] = [1.0, 4.0] con los defaults). Tunables documentados
    // igual que PRICE_BAND_PCT — ver Open Question 0.2 en tasks.md.
    private static final double FEEDBACK_BOOST_STEP = 1.0;
    private static final int    FEEDBACK_BOOST_CAP   = 3;

    /** categoria → slot, por taxonomía de design.md / spec.md. */
    private static final Map<String, String> CATEGORIA_SLOT = buildCategoriaSlotMap();

    /**
     * Regla de elegibilidad por estilo: whitelists nullable por slot — null significa
     * "sin restricción de estilo, usar la taxonomía base de ese slot". Gym restringe
     * los cuatro slots (calzado, torso, piernas, accesorio); estilos futuros pueden
     * restringir solo algunos y dejar el resto en null.
     */
    private record StyleRule(
            Set<String> calzadoWhitelist   /* nullable */,
            Set<String> torsoWhitelist     /* nullable */,
            Set<String> piernasWhitelist   /* nullable */,
            Set<String> accesorioWhitelist /* nullable */) { }

    private static final Map<String, StyleRule> STYLE_RULES = Map.of(
            "gym", new StyleRule(
                    Set.of("Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
                            "Zapatilla Urbana", "Sneaker"),
                    Set.of("Buzo", "Campera", "Remera", "Musculosa"),
                    Set.of("Short", "Pantalón", "Calza"),
                    Set.of("Gorra", "Medias", "Suplemento"))
            // Excluidos a propósito para Gym: Botines/Borcego/Botas/Ojotas/Zapatilla
            // Skate (calzado — skate no es training, ej. DC/Vans); Sweater/Camisa/
            // Chomba/Casaca/Chaleco/Saco/Traje/Piloto/Puffer (torso); Baggy/Jean/
            // Bermuda/Pollera (piernas); Riñonera/Billetera/Cinturón/Bufanda/Guantes/
            // Gorro/Lentes (accesorio) — confirmado por el usuario.
    );
    private static final StyleRule DEFAULT_STYLE_RULE = new StyleRule(null, null, null, null); // sin restricción

    /**
     * Veto global (ajuste posterior a outfit-recommendation-quality): categorias
     * acá NUNCA son elegibles para su slot, bajo NINGÚN estilo — ni siquiera
     * DEFAULT_STYLE_RULE (whitelist null). Chequeado en slotDe() ANTES del gate
     * de estilo, así que es independiente de STYLE_RULES.
     */
    private static final Set<String> ACCESORIO_VETADO = Set.of("Mochila", "Bolso");

    /**
     * Veto de marca para calzado, Gym-only (no global — análogo a Borcego/Botas/
     * Ojotas): DC es marca de skate/lifestyle, no training, aunque el producto
     * puntual se clasifique como "Zapatilla" genérica (sin keyword de skate en
     * el nombre). Confirmado por el usuario tras verla aparecer en el armador.
     */
    private static final Set<String> CALZADO_MARCA_VETADA_GYM = Set.of("DC");

    /**
     * Veto global (ADR-2 de outfit-per-item-feedback): categorias acá NUNCA son
     * elegibles para el slot calzado, bajo NINGÚN estilo — ni siquiera
     * DEFAULT_STYLE_RULE (whitelist null). Chequeado en slotDe() ANTES del gate
     * de estilo, así que es independiente de STYLE_RULES.
     * Borcego/Botas/Ojotas NO están acá — siguen gobernados solo por el
     * whitelist Gym-only de STYLE_RULES.
     */
    private static final Set<String> CALZADO_VETADO = Set.of("Botines");

    /**
     * Union of all canonical categories across the four taxonomy groups
     * (Torso / Piernas / Calzado / Accesorio). Used by the Budget Builder
     * endpoint to reject or ignore unknown category names sent by the client.
     */
    public static final Set<String> KNOWN_CATEGORIAS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // Torso
                    "Puffer", "Campera", "Sweater", "Buzo", "Musculosa", "Camisa", "Remera",
                    "Chomba", "Casaca", "Chaleco", "Saco", "Traje", "Piloto",
                    // Piernas
                    "Calza", "Baggy", "Jean", "Jogging", "Short", "Bermuda", "Pollera", "Pantalón",
                    // Calzado
                    "Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
                    "Zapatilla Skate", "Zapatilla Urbana", "Sneaker",
                    "Botines", "Borcego", "Botas", "Ojotas",
                    // Accesorio
                    "Mochila", "Bolso", "Riñonera", "Billetera", "Cinturón", "Bufanda",
                    "Guantes", "Gorro", "Gorra", "Lentes", "Medias", "Suplemento"
            )));

    // Sub-slot keys for the budget builder (armarPorCategorias).
    // Torso is split into base + outer layers; accesorio into head/feet/body.
    // Piernas and calzado remain single-pick and reuse their slot key directly.
    static final String SUBSLOT_TORSO_BASE      = "torso-base";
    static final String SUBSLOT_TORSO_OUTER     = "torso-outer";
    static final String SUBSLOT_ACCESORIO_HEAD  = "accesorio-head";
    static final String SUBSLOT_ACCESORIO_FEET  = "accesorio-feet";
    static final String SUBSLOT_ACCESORIO_BODY  = "accesorio-body";

    // Package-private, not private: OutfitBudgetBuilder reads it. Still DECLARED
    // on this class, which is what OutfitServiceSubslotTest's getDeclaredField needs.
    static final Map<String, String> CATEGORIA_SUBSLOT = buildCategoriaSubslotMap();

    private static Map<String, String> buildCategoriaSubslotMap() {
        Map<String, String> m = new HashMap<>();
        for (String cat : List.of("Remera", "Musculosa", "Camisa", "Chomba"))
            m.put(cat, SUBSLOT_TORSO_BASE);
        for (String cat : List.of("Buzo", "Campera", "Sweater", "Puffer", "Casaca", "Chaleco", "Saco", "Traje", "Piloto"))
            m.put(cat, SUBSLOT_TORSO_OUTER);
        for (String cat : List.of("Calza", "Baggy", "Jean", "Jogging", "Short", "Bermuda", "Pollera", "Pantalón"))
            m.put(cat, SLOT_PIERNAS);
        for (String cat : List.of("Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
                "Zapatilla Skate", "Zapatilla Urbana", "Sneaker", "Botines", "Borcego", "Botas", "Ojotas"))
            m.put(cat, SLOT_CALZADO);
        for (String cat : List.of("Gorra", "Gorro"))
            m.put(cat, SUBSLOT_ACCESORIO_HEAD);
        m.put("Medias", SUBSLOT_ACCESORIO_FEET);
        for (String cat : List.of("Riñonera", "Cinturón", "Lentes", "Bufanda", "Guantes", "Billetera"))
            m.put(cat, SUBSLOT_ACCESORIO_BODY);
        // Mochila, Bolso, Suplemento are excluded (vetoed or handled separately)
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> buildCategoriaSlotMap() {
        Map<String, String> m = new HashMap<>();
        for (String cat : List.of(
                "Puffer", "Campera", "Sweater", "Buzo", "Musculosa", "Camisa", "Remera",
                "Chomba", "Casaca", "Chaleco", "Saco", "Traje", "Piloto")) {
            m.put(cat, SLOT_TORSO);
        }
        for (String cat : List.of(
                "Calza", "Baggy", "Jean", "Jogging", "Short", "Bermuda", "Pollera", "Pantalón")) {
            m.put(cat, SLOT_PIERNAS);
        }
        for (String cat : List.of(
                "Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
                "Zapatilla Skate", "Zapatilla Urbana", "Sneaker",
                "Botines", "Borcego", "Botas", "Ojotas")) {
            m.put(cat, SLOT_CALZADO);
        }
        for (String cat : List.of(
                "Mochila", "Bolso", "Riñonera", "Billetera", "Cinturón", "Bufanda",
                "Guantes", "Gorro", "Gorra", "Lentes", "Medias", "Suplemento")) {
            m.put(cat, SLOT_ACCESORIO);
        }
        return Collections.unmodifiableMap(m);
    }

    /** Resultado de un slot individual dentro de un outfit generado. */
    public record SlotPick(
            String slot, String sitio, String nombre, double precio,
            String url, String img, String categoria, String marca) {
    }

    /** Resultado completo de armar() — outfit con slots, genero usado, flag partial, total y flag de presupuesto. */
    public record Outfit(List<SlotPick> slots, String genero, boolean partial,
                         double totalEstimado, boolean presupuestoExcedido) {
    }

    /**
     * Result of {@link #armarPorCategorias}: globally-optimal product picks
     * within the requested budget, or an empty set when no valid combination
     * fits. Never exceeds {@code presupuesto} — the hard-budget invariant is
     * always enforced.
     *
     * @param slots                  chosen products (SlotPick.slot == categoria)
     * @param genero                 gender filter applied (empty = no filter)
     * @param presupuesto            the original budget ceiling
     * @param totalEstimado          sum of selected item prices (always ≤ presupuesto)
     * @param noCumplePresupuesto    true when ≥1 category had candidates but
     *                               the optimizer could not include them within budget
     * @param categoriasVacias       categories with no eligible products after
     *                               catalog + gender + gymrat filter (catalog gap)
     * @param categoriasSinPresupuesto categories that had products but none fit
     *                               within the remaining budget during optimization
     * @param minimoBudgetNecesario  sum of cheapest eligible product per category
     *                               (null = at least one category has no eligible products)
     */
    public record OutfitBuilderResult(
            List<SlotPick> slots,
            String genero,
            double presupuesto,
            double totalEstimado,
            boolean noCumplePresupuesto,
            List<String> categoriasVacias,
            List<String> categoriasSinPresupuesto,
            Double minimoBudgetNecesario) {
    }

    /**
     * Un subtipo ofrecible del combo de suplementos, con su grupo de selector
     * ({@code null} = "Otros"). Sirve al selector del frontend, que mantenía su propia
     * copia a mano de esta lista.
     */
    public record SupplementTipo(String tipo, String grupo) { }

    /**
     * Nombres de los subtipos. Existe para que un test pueda afirmar que el endpoint no
     * se queda corto respecto de lo que el builder puede devolver — un subtipo que el
     * builder elige pero la lista no anuncia es inseleccionable en la UI.
     */
    public static final List<String> TIPOS_SUPLEMENTO = SupplementCombo.tiposDisponibles()
            .stream().map(SupplementTipo::tipo).toList();

    /** Resultado de un ítem del combo de suplementos (independiente de los slots del outfit). */
    public record SupplementPick(
            String tipo, String sitio, String nombre, double precio,
            String url, String img, String marca) {
    }

    // ─── Combo de suplementos. Bodies in SupplementCombo (backlog A3);
    // this class keeps the public surface and delegates. SupplementPick stays
    // nested here: callers and tests name it OutfitService.SupplementPick.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Combo de suplementos a mostrar siempre junto al outfit, independiente de
     * género/estilo — best-effort por subtipo (subtipo sin candidatos se omite).
     * Backward-compat overload: sin límite de presupuesto.
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos) {
        return supplementCombo.armarComboSuplementos(productos);
    }

    /**
     * Combo de suplementos con presupuesto independiente opcional.
     * presupuesto=0 → sin límite. Budget-aware: por subtipo, filtra candidatos por
     * precio ≤ remaining; si ninguno cabe, elige el más barato (no bloquea el slot).
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto) {
        return supplementCombo.armarComboSuplementos(productos, presupuesto);
    }

    /**
     * Combo de suplementos filtrado por tipos solicitados. tipos vacío o null →
     * usa todos los subtipos (backward-compat con el overload de 2 args).
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto, Set<String> tipos) {
        return supplementCombo.armarComboSuplementos(productos, presupuesto, tipos);
    }

    /**
     * Modelo de feedback (ADR-1/ADR-2 en design.md de outfit-recommendation-quality):
     * exclude = pares marca|categoria con al menos un dislike (veto duro, permanente);
     * boostLikeCount = cantidad de likes por par marca|categoria (folding en
     * weightedRandomPick, Fase 2 — Task 2.6; no usado todavía en esta fase).
     * excludeCategoria = categorias bare (sin marca) marcadas "no me interesa"
     * feed-wide (Decision 1 de design.md, personalized-recommendations-feed) —
     * eje de exclusión SEGUNDO e independiente del pair-exclude existente; un
     * producto se excluye si su categoria bare está acá, sin importar marca,
     * incluyendo productos sin marca de esa categoria. NO afecta exclude/
     * boostLikeCount existentes.
     * Construido por ApiController.buildFeedbackModel() a partir de
     * DatabaseService.obtenerOutfitFeedback() + DatabaseService.obtenerCategoriaDismiss()
     * + el catálogo vivo (OutfitService permanece DB-agnostic, ADR-3 de outfit-builder).
     */
    public record FeedbackModel(Set<String> exclude, Map<String, Integer> boostLikeCount,
                                 Set<String> excludeCategoria) {
        public static FeedbackModel empty() {
            return new FeedbackModel(Set.of(), Map.of(), Set.of());
        }

        /** marca|categoria, null-safe — null/blank colapsa al lado vacío de la key. */
        public static String keyOf(Product p) {
            String marca     = p.marca()     != null ? p.marca().trim()     : "";
            String categoria = p.categoria() != null ? p.categoria().trim() : "";
            return marca + "|" + categoria;
        }
    }

    /**
     * categoria → slot, dependiente del estilo activo (ADR-3). Footwear (ADR-1) usa
     * esCalzadoElegible(rule, cat) independientemente de gymrat; torso/piernas SÍ
     * exigen gymrat==true (chequeado en armar(), no aquí). categorias fuera de la
     * taxonomía, o calzado no elegible bajo el estilo activo, no entran a ningún slot
     * — NO debe caer al fallback de CATEGORIA_SLOT.get(cat) para calzado, porque eso
     * reintroduciría una segunda vía hacia SLOT_CALZADO que saltea el gate de estilo.
     */
    private String slotDe(Product p, StyleRule rule) {
        String cat = p.categoria();
        if (cat == null || cat.isBlank()) return null;
        if (ACCESORIO_VETADO.contains(cat)) return null; // global, style-independent
        if (CALZADO_VETADO.contains(cat)) return null; // global, style-independent
        if (esCalzadoBase(cat)) {
            if (!esCalzadoElegible(rule, cat)) return null;
            if (rule.calzadoWhitelist() != null
                    && CALZADO_MARCA_VETADA_GYM.contains(p.marca())) return null;
            return SLOT_CALZADO;
        }
        String slot = CATEGORIA_SLOT.get(cat);
        if (slot == null) return null;
        return slotWhitelist(rule, slot) == null || slotWhitelist(rule, slot).contains(cat)
                ? slot : null;
    }

    /** Whitelist activa para un slot no-calzado bajo la StyleRule dada (null = sin restricción). */
    private Set<String> slotWhitelist(StyleRule rule, String slot) {
        return switch (slot) {
            case SLOT_TORSO -> rule.torsoWhitelist();
            case SLOT_PIERNAS -> rule.piernasWhitelist();
            case SLOT_ACCESORIO -> rule.accesorioWhitelist();
            default -> null;
        };
    }

    /**
     * Taxonomía base de calzado, independiente de estilo (ADR-1): esGymrat() siempre
     * devuelve false para calzado (guard en NormalizerService), así que el slot
     * calzado filtra por categoria directamente, sin tocar esCalzado()/esGymrat().
     * Usado como fallback de DEFAULT_STYLE_RULE (sin restricción de estilo) y como
     * guard en slotDe() para decidir si una categoria pertenece a la familia calzado
     * antes de aplicar el whitelist de estilo.
     */
    private boolean esCalzadoBase(String categoria) {
        if (categoria == null) return false;
        return categoria.startsWith("Zapatilla")
                || categoria.equals("Botines")
                || categoria.equals("Borcego")
                || categoria.equals("Botas")
                || categoria.equals("Ojotas")
                || categoria.equals("Sneaker");
    }

    /**
     * Elegibilidad de calzado bajo el estilo activo (ADR-3): si la regla no restringe
     * calzado (whitelist null), cualquier categoria de la taxonomía base es elegible.
     * Si restringe (p.ej. Gym), solo las categorias explícitamente listadas lo son —
     * Botines/Borcego/Botas/Ojotas quedan afuera para Gym aunque sigan siendo parte de
     * la taxonomía general de calzado (Slot Taxonomy).
     */
    private boolean esCalzadoElegible(StyleRule rule, String categoria) {
        if (rule.calzadoWhitelist() == null) return esCalzadoBase(categoria);
        return categoria != null && rule.calzadoWhitelist().contains(categoria);
    }

    /**
     * Genero Matching Policy: requested == valor OR "unisex" OR vacío/null.
     * Un pedido genero=unisex (o ausente) matchea CUALQUIER genero del producto
     * (spec: "MUST match products whose genero is unisex, empty/missing, OR any
     * gendered value") — bug fix: antes un pedido "unisex" explícito caía en la
     * comparación estricta de la última línea y excluía productos con genero
     * "hombre"/"mujer", lo que también dejaba sin efecto el fallback paso 2.
     * Excepción dura: genero=="infantil" (NormalizerService.normalizarGenero)
     * nunca es elegible, ni siquiera pidiendo "unisex" — el armador es para
     * adultos, confirmado por el usuario tras ver zapatillas de niños en Gym.
     */
    private boolean generoElegible(Product p, String generoSolicitado) {
        return OutfitRules.generoElegible(p, generoSolicitado);
    }

    /**
     * Armar un outfit Gym para el genero solicitado (o "" / null → unisex-eligible).
     * Overload de compatibilidad (ADR-3, Open Question 0.3, confirmado por Task 2.5):
     * delega al 4-arg con estilo="gym" y sin feedback — no-op de exclude/boost,
     * comportamiento idéntico al pre-existente. Se mantiene aunque la búsqueda de
     * callers (Task 2.5) solo encontró ApiController, para no forzar un cambio en
     * eventuales callers futuros/tests.
     */
    public Outfit armar(List<Product> productos, String generoSolicitado) {
        return armar(productos, generoSolicitado, "gym", FeedbackModel.empty());
    }

    /**
     * Overload de compatibilidad 4-arg: delega al 6-arg con presupuesto=0 (sin límite)
     * y sin excluirUrls. Comportamiento idéntico al pre-existente.
     */
    public Outfit armar(List<Product> productos, String generoSolicitado, String estilo, FeedbackModel feedback) {
        return armar(productos, generoSolicitado, estilo, feedback, 0, Set.of());
    }

    /**
     * Armar un outfit para el genero y estilo solicitados, con feedback de
     * usuario aplicado, presupuesto opcional y URLs a excluir por slot-swap.
     *
     * presupuesto=0 → sin límite de presupuesto (comportamiento original).
     * excluirUrls → URLs de productos a excluir (slot-swap del usuario).
     * Budget-aware selection: por slot, si presupuesto > 0, filtra candidatos por
     * precio ≤ (presupuesto - runningTotal). Si ninguno cabe, usa el pool completo
     * (fallback — mejor dar un outfit completo que uno parcial por presupuesto).
     */
    public Outfit armar(List<Product> productos, String generoSolicitado, String estilo,
                        FeedbackModel feedback, double presupuesto, Set<String> excluirUrls) {
        if (productos == null) productos = List.of();
        if (feedback == null) feedback = FeedbackModel.empty();
        if (excluirUrls == null) excluirUrls = Set.of();
        Set<String> exclude = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();
        StyleRule rule = STYLE_RULES.getOrDefault(estilo, DEFAULT_STYLE_RULE);

        // 1. Particionar por slot, solo gymrat (torso/piernas) o calzado elegible
        //    bajo la StyleRule activa.
        //    Hard exclude (ADR-2): se descarta cualquier producto cuyo marca|categoria
        //    esté en feedback.exclude() ANTES de que corra el fallback de 3 pasos.
        //    Segundo eje (personalized-recommendations-feed, Decision 1): se descarta
        //    también cualquier producto cuya categoria bare esté en excludeCategoria,
        //    sin importar marca — independiente del pair-exclude anterior.
        //    excluirUrls: exclusión a nivel URL (slot-swap por el usuario).
        final Set<String> excluirUrlsFinal = excluirUrls;
        Map<String, List<Product>> bySlot = new HashMap<>();
        for (Product p : productos) {
            String slot = slotDe(p, rule);
            if (slot == null) continue;
            if (exclude.contains(FeedbackModel.keyOf(p))) continue;
            if (excludeCategoria.contains(p.categoria())) continue;
            if (excluirUrlsFinal.contains(p.url())) continue;
            if (SLOT_TORSO.equals(slot) || SLOT_PIERNAS.equals(slot)) {
                if (!p.gymrat()) continue;
            } else if (SLOT_CALZADO.equals(slot)) {
                // calzado: whitelist ya aplicado en slotDe(), no requiere gymrat
            }
            // accesorio: sin filtro adicional, sigue elegible por genero/precio igual que el resto
            bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(p);
        }

        // 2. Banda de precio ±PRICE_BAND_PCT sobre la mediana del pool elegible
        //    (gymrat torso+piernas+calzado, ya filtrado por genero).
        List<Product> poolElegible = bySlot.values().stream()
                .flatMap(List::stream)
                .filter(p -> generoElegible(p, generoSolicitado))
                .collect(Collectors.toList());
        double[] band = priceBand(poolElegible);

        boolean partial = false;
        Map<String, SlotPick> picks = new LinkedHashMap<>();
        double runningTotal = 0.0;

        for (String slot : SLOTS_REQUERIDOS) {
            List<Product> base = bySlot.getOrDefault(slot, List.of());

            // Budget-aware candidate pre-filtering: si hay presupuesto activo,
            // intentar primero candidatos que quepan en el restante. Si ninguno
            // cabe, usar el pool completo (fallback — outfit completo > outfit parcial).
            List<Product> baseFiltered = base;
            if (presupuesto > 0) {
                double remaining = presupuesto - runningTotal;
                List<Product> affordable = base.stream()
                        .filter(p -> p.precio() <= remaining)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) baseFiltered = affordable;
                // else: fallback al pool completo del slot
            }

            // Paso 0: genero + banda de precio
            List<Product> cands = filtrar(baseFiltered, generoSolicitado, band[0], band[1]);

            // Paso 1: relajar banda de precio (mantener genero)
            if (cands.isEmpty()) {
                cands = filtrar(baseFiltered, generoSolicitado, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            }

            // Paso 2: relajar a productos sin género o explícitamente unisex.
            // NO usar generoElegible(p, "unisex") — esa ruta devuelve true para
            // TODOS los géneros (spec de compatibilidad), lo que cuela productos
            // del género opuesto cuando el catálogo de un slot es pequeño.
            if (cands.isEmpty()) {
                cands = baseFiltered.stream()
                        .filter(p -> { String g = p.genero() != null ? p.genero().trim() : "";
                                       return g.isEmpty() || "unisex".equalsIgnoreCase(g); })
                        .collect(Collectors.toList());
            }

            // Paso 3: sin candidatos tras ambas relajaciones → partial, sin fabricar producto
            if (cands.isEmpty()) {
                partial = true;
                continue;
            }

            Product elegido = weightedRandomPick(cands, band, feedback.boostLikeCount());
            picks.put(slot, toSlotPick(slot, elegido));
            runningTotal += elegido.precio();
        }

        // Accesorio: best-effort, sin fallback (ADR confirmado en design.md / spec).
        // También aplica budget-aware filtering si hay presupuesto activo.
        List<Product> accesorios = bySlot.getOrDefault(SLOT_ACCESORIO, List.of());
        List<Product> accesoriosElegibles = filtrar(accesorios, generoSolicitado,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        if (!accesoriosElegibles.isEmpty()) {
            List<Product> accesorioPool = accesoriosElegibles;
            if (presupuesto > 0) {
                double remaining = presupuesto - runningTotal;
                List<Product> affordable = accesoriosElegibles.stream()
                        .filter(p -> p.precio() <= remaining)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) accesorioPool = affordable;
            }
            Product accesorio = weightedRandomPick(accesorioPool, band, feedback.boostLikeCount());
            picks.put(SLOT_ACCESORIO, toSlotPick(SLOT_ACCESORIO, accesorio));
        }

        List<SlotPick> ordenados = new ArrayList<>();
        for (String slot : List.of(SLOT_TORSO, SLOT_PIERNAS, SLOT_CALZADO, SLOT_ACCESORIO)) {
            SlotPick pick = picks.get(slot);
            if (pick != null) ordenados.add(pick);
        }

        String generoResultado = (generoSolicitado != null && !generoSolicitado.isBlank())
                ? generoSolicitado : "unisex";
        double totalEstimado = ordenados.stream().mapToDouble(SlotPick::precio).sum();
        boolean presupuestoExcedido = presupuesto > 0 && totalEstimado > presupuesto;
        return new Outfit(ordenados, generoResultado, partial, totalEstimado, presupuestoExcedido);
    }

    /**
     * Style eligibility gate for the budget builder's torso/piernas sub-slots.
     * Calzado and accesorio always pass (their eligibility is category-driven,
     * style-independent — see armarPorCategorias / CATEGORIA_SUBSLOT).
     *
     * <ul>
     *   <li>{@code estilo="gym"} (default): torso/piernas require {@code gymrat==true}
     *       — training-oriented apparel only, mirroring the pre-existing hardcoded gate.</li>
     *   <li>{@code estilo="casual"}: torso/piernas require {@code gymrat==false} — everyday
     *       apparel. Since "not gymrat" is the whole casual universe (confirmed with the
     *       user: non-gymrat isn't formal, so it's casual), new casual sites become eligible
     *       automatically without a site whitelist.</li>
     * </ul>
     */
    private boolean pasaEstiloGate(Product p, String slot, String estilo) {
        return OutfitRules.pasaEstiloGate(p, slot, estilo);
    }

    private List<Product> filtrar(List<Product> base, String generoSolicitado, double min, double max) {
        return base.stream()
                .filter(p -> generoElegible(p, generoSolicitado))
                .filter(p -> p.precio() >= min && p.precio() <= max)
                .collect(Collectors.toList());
    }

    /** Banda [min,max] = mediana ± PRICE_BAND_PCT. Si no hay pool, banda abierta (sin restricción). */
    private double[] priceBand(List<Product> pool) {
        if (pool.isEmpty()) {
            return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        }
        double[] precios = pool.stream().mapToDouble(Product::precio).sorted().toArray();
        double mediana = precios[precios.length / 2];
        double min = mediana * (1 - PRICE_BAND_PCT);
        double max = mediana * (1 + PRICE_BAND_PCT);
        return new double[]{min, max};
    }

    /**
     * Selección aleatoria ponderada: candidatos más cercanos a la mediana de la
     * banda de precio reciben mayor peso, para favorecer coherencia económica
     * sin descartar variedad. El peso base se multiplica por un boostFactor
     * derivado de boostLikeCount (ADR-2): pares con más likes (hasta
     * FEEDBACK_BOOST_CAP) ganan más peso, sin volverse unbounded. El early-return
     * de candidatos.size()==1 se mantiene — es seguro porque el exclude ya corrió
     * upstream en armar(), así que un único candidato no puede ser un par excluido,
     * y el boost es irrelevante para una elección forzada.
     *
     * distancia se normaliza por la mitad del ancho de banda (escala relativa,
     * no pesos absolutos) — bug encontrado en vivo: con distancia en pesos
     * crudos, un candidato a pocos pesos del centro (ej. coincidencia de
     * $9 en una banda de $36000) pesaba ~1000x más que el resto y ganaba
     * casi siempre, colapsando la variedad para categorías de ticket alto
     * (calzado) donde esa coincidencia es más probable por la granularidad
     * de precios del catálogo.
     */
    private Product weightedRandomPick(List<Product> candidatos, double[] band,
                                        Map<String, Integer> boostLikeCount) {
        if (candidatos.size() == 1) return candidatos.get(0);

        double centro = (Double.isFinite(band[0]) && Double.isFinite(band[1]))
                ? (band[0] + band[1]) / 2.0
                : candidatos.stream().mapToDouble(Product::precio).average().orElse(0);

        double mitadBanda = (Double.isFinite(band[0]) && Double.isFinite(band[1]) && band[1] > band[0])
                ? (band[1] - band[0]) / 2.0
                : Math.max(centro * PRICE_BAND_PCT, 1.0);

        double[] pesos = new double[candidatos.size()];
        double totalPeso = 0;
        for (int i = 0; i < candidatos.size(); i++) {
            Product c = candidatos.get(i);
            double distancia = Math.abs(c.precio() - centro) / mitadBanda;
            double likeCount = boostLikeCount.getOrDefault(FeedbackModel.keyOf(c), 0);
            double boostFactor = 1.0 + Math.min(likeCount, FEEDBACK_BOOST_CAP) * FEEDBACK_BOOST_STEP;
            double peso = (1.0 / (1.0 + distancia)) * boostFactor;
            pesos[i] = peso;
            totalPeso += peso;
        }

        double r = ThreadLocalRandom.current().nextDouble() * totalPeso;
        double acumulado = 0;
        for (int i = 0; i < candidatos.size(); i++) {
            acumulado += pesos[i];
            if (r <= acumulado) return candidatos.get(i);
        }
        return candidatos.get(candidatos.size() - 1);
    }

    // ─── Budget Builder (MCKP). Bodies in OutfitBudgetBuilder (backlog A3);
    // this class keeps the public surface and delegates. The four overloads
    // mirror the originals exactly: 5, 7, 8 and 9 args.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Backward-compatible 5-arg overload. Delegates with no exclusions and MCKP mode.
     */
    public OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, FeedbackModel feedback) {
        return budgetBuilder.armarPorCategorias(productos, categorias, presupuesto, genero, feedback);
    }

    public OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy) {
        return budgetBuilder.armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy);
    }

    public OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy, List<Product> pinned) {
        return budgetBuilder.armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy, pinned);
    }

    public OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy, List<Product> pinned, String estilo) {
        return budgetBuilder.armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy, pinned, estilo);
    }

    /** Delegates to {@link OutfitRules}; kept private so the assembler above is untouched. */
    private SlotPick toSlotPick(String slot, Product p) {
        return OutfitRules.toSlotPick(slot, p);
    }
}
