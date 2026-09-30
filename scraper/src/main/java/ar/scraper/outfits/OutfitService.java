package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.Comparator;
import org.apache.commons.lang3.StringUtils;

public class OutfitService {

    private final RecommendationService recommendationService;

    /**
     * Built here rather than injected so this constructor's shape stays unchanged for the existing
     * test call sites — it takes the same RecommendationService this class already receives, for
     * the score tiebreak in its pick ranking.
     */
    private final SupplementCombo supplementCombo;

    /**
     * Built here rather than injected so this constructor's shape stays unchanged for the existing
     * test call sites.
     */
    private final OutfitBudgetBuilder budgetBuilder;

    public OutfitService(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
        this.budgetBuilder = new OutfitBudgetBuilder(recommendationService);
        this.supplementCombo = new SupplementCombo(recommendationService);
    }

    public static final String SLOT_TORSO     = "torso";
    public static final String SLOT_PIERNAS   = "piernas";
    public static final String SLOT_CALZADO   = "calzado";
    public static final String SLOT_ACCESORIO = "accesorio";

    private static final List<String> SLOTS_REQUERIDOS =
            List.of(SLOT_TORSO, SLOT_PIERNAS, SLOT_CALZADO);

    private static final double PRICE_BAND_PCT = OutfitRules.PRICE_BAND_PCT;

    private static final double FEEDBACK_BOOST_STEP = OutfitRules.FEEDBACK_BOOST_STEP;
    private static final int    FEEDBACK_BOOST_CAP   = OutfitRules.FEEDBACK_BOOST_CAP;

    // El armador aleatorio pesaba solo por distancia de precio y likes: dentro de una misma banda,
    // un fake_discount y un all_time_low eran igual de probables — mientras el budget builder
    // maximiza exactamente esa señal y el feed "Para ti" ordena por ella.
    private static final double ML_SCORE_NEUTRO = OutfitRules.ML_SCORE_NEUTRO;
    private static final double ML_FACTOR_MIN   = OutfitRules.ML_FACTOR_MIN;
    private static final double ML_FACTOR_MAX   = OutfitRules.ML_FACTOR_MAX;

    private static final Map<String, String> CATEGORIA_SLOT = buildCategoriaSlotMap();

    /**
     * Regla de elegibilidad por estilo: whitelists nullable por slot — null significa "sin
     * restricción de estilo, usar la taxonomía base de ese slot".
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
            // Botines/Borcego/Botas/Ojotas/Zapatilla Skate (calzado — skate no es training, ej.
            // DC/Vans);
    );
    private static final StyleRule DEFAULT_STYLE_RULE = new StyleRule(null, null, null, null); // sin restricción

    private static final Set<String> ACCESORIO_VETADO = Set.of("Mochila", "Bolso");

    /** Veto de marca para calzado, Gym-only (no global — análogo a Borcego/Botas/ Ojotas): */
    private static final Set<String> CALZADO_MARCA_VETADA_GYM = Set.of("DC");

    /**
     * Borcego/Botas/Ojotas NO están acá — siguen gobernados solo por el whitelist Gym-only de
     * STYLE_RULES.
     */
    private static final Set<String> CALZADO_VETADO = Set.of("Botines");

    /**
     * Used by the Budget Builder endpoint to reject or ignore unknown category names sent by the
     * client.
     */
    public static final Set<String> KNOWN_CATEGORIAS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "Puffer", "Campera", "Sweater", "Buzo", "Musculosa", "Camisa", "Remera",
                    "Chomba", "Casaca", "Chaleco", "Saco", "Traje", "Piloto",
                    "Calza", "Baggy", "Jean", "Jogging", "Short", "Bermuda", "Pollera", "Pantalón",
                    "Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
                    "Zapatilla Skate", "Zapatilla Urbana", "Sneaker",
                    "Botines", "Borcego", "Botas", "Ojotas",
                    "Mochila", "Bolso", "Riñonera", "Billetera", "Cinturón", "Bufanda",
                    "Guantes", "Gorro", "Gorra", "Lentes", "Medias", "Suplemento"
            )));

    static final String SUBSLOT_TORSO_BASE      = "torso-base";
    static final String SUBSLOT_TORSO_OUTER     = "torso-outer";
    static final String SUBSLOT_ACCESORIO_HEAD  = "accesorio-head";
    static final String SUBSLOT_ACCESORIO_FEET  = "accesorio-feet";
    static final String SUBSLOT_ACCESORIO_BODY  = "accesorio-body";

    // Package-private, not private:
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

    public record SlotPick(
            String slot, String sitio, String nombre, double precio,
            String url, String img, String categoria, String marca) {
    }

    public record Outfit(List<SlotPick> slots, String genero, boolean partial,
                         double totalEstimado, boolean presupuestoExcedido) {
    }

    /** Never exceeds {@code presupuesto} — the hard-budget invariant is always enforced.. */
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

    public record SupplementTipo(String tipo, String grupo) { }

    /**
     * Existe para que un test pueda afirmar que el endpoint no se queda corto respecto de lo que el
     * builder puede devolver — un subtipo que el builder elige pero la lista no anuncia es
     * inseleccionable en la UI.
     */
    public static final List<String> TIPOS_SUPLEMENTO = SupplementCombo.tiposDisponibles()
            .stream().map(SupplementTipo::tipo).toList();

    public record SupplementPick(
            String tipo, String sitio, String nombre, double precio,
            String url, String img, String marca) {
    }

    /**
     * Combo de suplementos a mostrar siempre junto al outfit, independiente de género/estilo —
     * best-effort por subtipo (subtipo sin candidatos se omite). Backward-compat overload: sin
     * límite de presupuesto.
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos) {
        return supplementCombo.armarComboSuplementos(productos);
    }

    /** Combo de suplementos con presupuesto independiente opcional. presupuesto=0 → sin límite. */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto) {
        return supplementCombo.armarComboSuplementos(productos, presupuesto);
    }

    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto, Set<String> tipos) {
        return supplementCombo.armarComboSuplementos(productos, presupuesto, tipos);
    }

    /**
     * Combo con URLs a excluir — lo que el usuario ya vio, para que "Regenerar" ofrezca el
     * siguiente en vez de repetir.
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto,
                                                      Set<String> tipos, Set<String> excluirUrls) {
        return supplementCombo.armarComboSuplementos(productos, presupuesto, tipos, excluirUrls);
    }

    /**
     * Modelo de feedback: exclude = pares marca|categoria con al menos un dislike (veto duro,
     * permanente); boostLikeCount = cantidad de likes por par marca|categoria. excludeCategoria =
     * categorias bare (sin marca) marcadas "no me interesa" feed-wide — eje de exclusión SEGUNDO e
     * independiente del pair-exclude existente; un producto se excluye si su categoria bare está
     * acá, sin importar marca, incluyendo productos sin marca de esa categoria.
     */
    public record FeedbackModel(Set<String> exclude, Map<String, Integer> boostLikeCount,
                                 Set<String> excludeCategoria) {
        public static FeedbackModel empty() {
            return new FeedbackModel(Set.of(), Map.of(), Set.of());
        }

        public static String keyOf(Product p) {
            String marca     = p.marca()     != null ? p.marca().trim()     : "";
            String categoria = p.categoria() != null ? p.categoria().trim() : "";
            return marca + "|" + categoria;
        }
    }

    /**
     * Footwear (ADR-1) usa esCalzadoElegible(rule, cat) independientemente de gymrat; torso/piernas
     * SÍ exigen gymrat==true (chequeado en armar(), no aquí). categorias fuera de la taxonomía, o
     * calzado no elegible bajo el estilo activo, no entran a ningún slot — NO debe caer al fallback
     * de CATEGORIA_SLOT.get(cat) para calzado, porque eso reintroduciría una segunda vía hacia
     * SLOT_CALZADO que saltea el gate de estilo.
     */
    private String slotDe(Product p, StyleRule rule) {
        String cat = p.categoria();
        if (StringUtils.isBlank(cat)) return null;
        if (ACCESORIO_VETADO.contains(cat)) return null;
        if (CALZADO_VETADO.contains(cat)) return null;
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

    /**
     * Whitelist activa para un slot no-calzado bajo la StyleRule dada (null = sin restricción).
     */
    private Set<String> slotWhitelist(StyleRule rule, String slot) {
        return switch (slot) {
            case SLOT_TORSO -> rule.torsoWhitelist();
            case SLOT_PIERNAS -> rule.piernasWhitelist();
            case SLOT_ACCESORIO -> rule.accesorioWhitelist();
            default -> null;
        };
    }

    /**
     * Taxonomía base de calzado, independiente de estilo (ADR-1): esGymrat() siempre devuelve false
     * para calzado (guard en NormalizerService), así que el slot calzado filtra por categoria
     * directamente, sin tocar esCalzado()/esGymrat().
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
     * Si restringe (p.ej. Gym), solo las categorias explícitamente listadas lo son —
     * Botines/Borcego/Botas/Ojotas quedan afuera para Gym aunque sigan siendo parte de la taxonomía
     * general de calzado (Slot Taxonomy).
     */
    private boolean esCalzadoElegible(StyleRule rule, String categoria) {
        if (rule.calzadoWhitelist() == null) return esCalzadoBase(categoria);
        return categoria != null && rule.calzadoWhitelist().contains(categoria);
    }

    /**
     * "MUST match products whose genero is unisex, empty/missing, OR any gendered value") — bug
     * fix: antes un pedido "unisex" explícito caía en la comparación estricta de la última línea y
     * excluía productos con genero "hombre"/"mujer", lo que también dejaba sin efecto el fallback
     * paso 2.
     */
    private boolean generoElegible(Product p, String generoSolicitado) {
        return OutfitRules.generoElegible(p, generoSolicitado);
    }

    /**
     * Overload de compatibilidad (ADR-3, Open Question 0.3, confirmado por Task 2.5): delega al
     * 4-arg con estilo="gym" y sin feedback — no-op de exclude/boost, comportamiento idéntico al
     * pre-existente.
     */
    public Outfit armar(List<Product> productos, String generoSolicitado) {
        return armar(productos, generoSolicitado, "gym", FeedbackModel.empty());
    }

    /**
     * Overload de compatibilidad 4-arg: delega al 6-arg con presupuesto=0 (sin límite) y sin
     * excluirUrls.
     */
    public Outfit armar(List<Product> productos, String generoSolicitado, String estilo, FeedbackModel feedback) {
        return armar(productos, generoSolicitado, estilo, feedback, 0, Set.of());
    }

    /**
     * Armar un outfit para el genero y estilo solicitados, con feedback de usuario aplicado,
     * presupuesto opcional y URLs a excluir por slot-swap. presupuesto=0 → sin límite de
     * presupuesto (comportamiento original). excluirUrls → URLs de productos a excluir (slot-swap
     * del usuario).
     */
    public Outfit armar(List<Product> productos, String generoSolicitado, String estilo,
                        FeedbackModel feedback, double presupuesto, Set<String> excluirUrls) {
        if (productos == null) productos = List.of();
        if (feedback == null) feedback = FeedbackModel.empty();
        if (excluirUrls == null) excluirUrls = Set.of();
        Set<String> exclude = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();
        StyleRule rule = STYLE_RULES.getOrDefault(estilo, DEFAULT_STYLE_RULE);

        // Particionar por slot, solo gymrat (torso/piernas) o calzado elegible bajo la StyleRule
        // activa.
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

        List<Product> poolElegible = bySlot.values().stream()
                .flatMap(List::stream)
                .filter(p -> generoElegible(p, generoSolicitado))
                .collect(Collectors.toList());
        double[] band = priceBand(poolElegible);

        boolean partial = false;
        Map<String, SlotPick> picks = new LinkedHashMap<>();
        Map<String, Product> elegidos = new LinkedHashMap<>();
        double runningTotal = 0.0;

        for (String slot : SLOTS_REQUERIDOS) {
            List<Product> base = bySlot.getOrDefault(slot, List.of());

            // Si ninguno cabe, usar el pool completo (fallback — outfit completo > outfit parcial).
            List<Product> baseFiltered = base;
            if (presupuesto > 0) {
                double remaining = presupuesto - runningTotal;
                List<Product> affordable = base.stream()
                        .filter(p -> p.precio() <= remaining)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) baseFiltered = affordable;
                // else: fallback al pool completo del slot
            }

            List<Product> cands = filtrar(baseFiltered, generoSolicitado, band[0], band[1]);

            if (cands.isEmpty()) {
                cands = filtrar(baseFiltered, generoSolicitado, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            }

            // Paso 2: relajar a productos sin género o explícitamente unisex.
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

            Product elegido = weightedRandomPick(cands, band, feedback.boostLikeCount(),
                    slot, elegidos);
            picks.put(slot, toSlotPick(slot, elegido));
            elegidos.put(slot, elegido);
            runningTotal += elegido.precio();
        }

        // Accesorio: best-effort, sin fallback.
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
            Product accesorio = weightedRandomPick(accesorioPool, band, feedback.boostLikeCount(),
                    SLOT_ACCESORIO, elegidos);
            picks.put(SLOT_ACCESORIO, toSlotPick(SLOT_ACCESORIO, accesorio));
        }

        List<SlotPick> ordenados = new ArrayList<>();
        for (String slot : List.of(SLOT_TORSO, SLOT_PIERNAS, SLOT_CALZADO, SLOT_ACCESORIO)) {
            SlotPick pick = picks.get(slot);
            if (pick != null) ordenados.add(pick);
        }

        String generoResultado = StringUtils.isNotBlank(generoSolicitado)
                ? generoSolicitado : "unisex";
        double totalEstimado = ordenados.stream().mapToDouble(SlotPick::precio).sum();
        boolean presupuestoExcedido = presupuesto > 0 && totalEstimado > presupuesto;
        return new Outfit(ordenados, generoResultado, partial, totalEstimado, presupuestoExcedido);
    }

    /**
     * {@code estilo="gym"} (default): torso/piernas require {@code gymrat==true} —
     * training-oriented apparel only, mirroring the pre-existing hardcoded gate.
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

    /** Si no hay pool, banda abierta (sin restricción). */
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
     * Selección aleatoria ponderada: candidatos más cercanos a la mediana de la banda de precio
     * reciben mayor peso, para favorecer coherencia económica sin descartar variedad.
     */
    private Product weightedRandomPick(List<Product> candidatos, double[] band,
                                        Map<String, Integer> boostLikeCount,
                                        String slot, Map<String, Product> yaElegidos) {
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
            double peso = (1.0 / (1.0 + distancia)) * boostFactor * mlFactor(c)
                    * VisualCoherence.coherencia(slot, c, yaElegidos);
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

    /**
     * Factor de oportunidad ML de un candidato, normalizado contra el score neutro (scoreP=50, sin
     * badges) y acotado a [{@value #ML_FACTOR_MIN}, {@value #ML_FACTOR_MAX}].
     */
    private double mlFactor(Product p) {
        return OutfitRules.mlFactor(recommendationService.baseMlScore(p));
    }

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

    private SlotPick toSlotPick(String slot, Product p) {
        return OutfitRules.toSlotPick(slot, p);
    }
}
