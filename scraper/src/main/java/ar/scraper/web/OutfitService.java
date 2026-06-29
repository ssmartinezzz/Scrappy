package ar.scraper.web;

import ar.scraper.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public OutfitService(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
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

    private static final Map<String, String> CATEGORIA_SUBSLOT = buildCategoriaSubslotMap();

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

    /** Resultado de un ítem del combo de suplementos (independiente de los slots del outfit). */
    public record SupplementPick(
            String tipo, String sitio, String nombre, double precio,
            String url, String img, String marca) {
    }

    private record SubtipoSuplemento(String tipo, String[] keywords) { }

    /**
     * Subtipos del combo de suplementos, en el orden en que se arma el combo.
     * Cada producto con categoria=="Suplemento" se reclasifica por nombre (no
     * toca el campo categoria canónico — evita romper el whitelist de accesorio
     * Gym ni los facets del dashboard, que dependen del string "Suplemento").
     */
    private static final List<SubtipoSuplemento> SUPLEMENTO_SUBTIPOS = List.of(
            new SubtipoSuplemento("Proteína en Polvo", new String[]{
                    "proteina", "protein", "whey", "isolate", "concentrate",
                    "caseina", "casein", "proteina isolada", "proteina hidrolizada"
            }),
            new SubtipoSuplemento("Barra Proteica", new String[]{
                    "barra proteica", "barra protein", "barra de proteina", "bar proteica",
                    "barra energetica", "barrita proteica", "barrita protein", "barrita"
            }),
            new SubtipoSuplemento("Pancake / Waffle", new String[]{
                    "pancake", "panqueque", "waffle", "hotcake proteico",
                    "preparo pancake", "mezcla pancake", "mix pancake"
            }),
            new SubtipoSuplemento("Snack Proteico", new String[]{
                    "snack proteico", "snack proteica",
                    "cookie proteica", "cookie protein",
                    "budín proteico", "budin proteico",
                    "muffin proteico", "brownie proteico", "alfajor proteico",
                    "tortita proteica", "galleta proteica"
            }),
            new SubtipoSuplemento("Creatina", new String[]{"creatina", "creatine", "monohidrato"}),
            new SubtipoSuplemento("Quemador", new String[]{"quemador", "fat burner", "termogenico", "carnitina", "cla "}),
            new SubtipoSuplemento("Vitamina C", new String[]{
                    "vitamina c", "vitamin c", "acido ascorbico", "ascórbico", "ascorbico"
            }),
            new SubtipoSuplemento("Multivitamínico", new String[]{
                    "multivitaminico", "multivitamin", "polivitaminico", "complejo vitaminico",
                    "complejo vitamínico", "multivit"
            }),
            new SubtipoSuplemento("Vitamina D", new String[]{
                    "vitamina d", "vitamin d", "colecalciferol", "vitamina d3", "vit d"
            }),
            new SubtipoSuplemento("Omega 3", new String[]{
                    "omega 3", "omega3", "omega-3", "aceite de pescado", "fish oil", "dha", "epa"
            }),
            new SubtipoSuplemento("Complejo B", new String[]{
                    "complejo b", "vitamina b", "vitaminas b", "b12", "b6", "b complex",
                    "cianocobalamina", "metilcobalamina"
            }),
            new SubtipoSuplemento("Zinc", new String[]{
                    "zinc", "gluconato de zinc", "picolinato de zinc", "citrato de zinc"
            }),
            new SubtipoSuplemento("Magnesio", new String[]{"magnesio", "magnesium", "citrato de magnesio"}),
            new SubtipoSuplemento("Aderezos", new String[]{
                    "mayonesa", "mayo fit", "mayonesa fit", "mayonesa light", "mayonesa proteica",
                    "ketchup", "ketchup fit", "ketchup zero",
                    "mostaza",
                    "maple", "jarabe de arce", "maple sin azucar", "maple zero",
                    "sirope", "sirope fit", "sirope zero",
                    "topping proteico", "topping fit",
                    "aderezo", "aderezo fit", "aderezo proteico",
                    "vinagre balsamico", "vinagre balsámico", "salsa fit", "salsa zero"
            })
    );

    /**
     * Orden de preferencia de marca para el combo de suplementos (confirmado por
     * el usuario): ENA y STAR ya tienen stock real en el catálogo; BCC ("La Roja")
     * no tiene productos hoy, pero queda en la lista para entrar sola el día que
     * se scrapee esa marca, sin tocar este código de nuevo.
     */
    private static final List<String> SUPLEMENTO_MARCA_PRIORIDAD = List.of("ENA", "STAR", "BCC");

    /** All canonical supplement categories assigned by NormalizerService. */
    private static final Set<String> CATEGORIAS_SUPLEMENTO = Set.of(
            "Suplemento", "Proteína", "Creatina", "Colágeno", "Magnesio",
            "Pre-Workout", "BCAA", "Vitaminas", "Quemadores", "Gainer", "Alimentos"
    );

    /**
     * Combo de suplementos (Proteína/Creatina/Quemador/Magnesio) a mostrar siempre
     * junto al outfit, independiente de género/estilo — best-effort por subtipo
     * (subtipo sin candidatos se omite del combo, mismo criterio que el accesorio
     * del armador de outfits).
     * Backward-compat overload: sin límite de presupuesto (comportamiento original).
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos) {
        return armarComboSuplementos(productos, 0);
    }

    /**
     * Combo de suplementos con presupuesto independiente opcional.
     * presupuesto=0 → sin límite (comportamiento original).
     * Budget-aware: por subtipo, filtra candidatos por precio ≤ remaining. Si ninguno
     * cabe dentro del presupuesto restante, elige el más barato disponible (no bloquea
     * el slot — combo completo > slot vacío).
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto) {
        if (productos == null) productos = List.of();
        List<Product> suplementos = productos.stream()
                .filter(p -> CATEGORIAS_SUPLEMENTO.contains(p.categoria()))
                .collect(Collectors.toList());

        List<SupplementPick> combo = new ArrayList<>();
        double remainingBudget = presupuesto;
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            List<Product> candidatos = suplementos.stream()
                    .filter(p -> matchesSubtipo(p.nombre(), subtipo.keywords()))
                    .collect(Collectors.toList());
            if (candidatos.isEmpty()) continue;

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = candidatos.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = elegirPorMarcaPrioridad(affordable);
                } else {
                    // Ningún candidato cabe — elige el más barato (no bloquea el slot)
                    elegido = candidatos.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElse(candidatos.get(0));
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = elegirPorMarcaPrioridad(candidatos);
            }
            combo.add(toSupplementPick(subtipo.tipo(), elegido));
        }
        return combo;
    }

    /**
     * Combo de suplementos filtrado por tipos solicitados (subset de SUPLEMENTO_SUBTIPOS).
     * tipos vacío o null → usa todos los subtipos (backward-compat con el overload de 2 args).
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto, Set<String> tipos) {
        if (productos == null) productos = List.of();
        List<Product> suplementos = productos.stream()
                .filter(p -> CATEGORIAS_SUPLEMENTO.contains(p.categoria()))
                .collect(Collectors.toList());

        List<SupplementPick> combo = new ArrayList<>();
        double remainingBudget = presupuesto;
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            if (tipos != null && !tipos.isEmpty() && !tipos.contains(subtipo.tipo())) continue;
            List<Product> candidatos = suplementos.stream()
                    .filter(p -> matchesSubtipo(p.nombre(), subtipo.keywords()))
                    .collect(Collectors.toList());
            if (candidatos.isEmpty()) continue;

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = candidatos.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = elegirPorMarcaPrioridad(affordable);
                } else {
                    elegido = candidatos.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElse(candidatos.get(0));
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = elegirPorMarcaPrioridad(candidatos);
            }
            combo.add(toSupplementPick(subtipo.tipo(), elegido));
        }
        return combo;
    }

    private boolean matchesSubtipo(String nombre, String[] keywords) {
        if (nombre == null || nombre.isBlank()) return false;
        String t = nombre.toLowerCase();
        for (String kw : keywords) {
            if (t.contains(kw)) return true;
        }
        return false;
    }

    private Product elegirPorMarcaPrioridad(List<Product> candidatos) {
        for (String marca : SUPLEMENTO_MARCA_PRIORIDAD) {
            for (Product p : candidatos) {
                if (marca.equalsIgnoreCase(p.marca())) return p;
            }
        }
        return candidatos.get(ThreadLocalRandom.current().nextInt(candidatos.size()));
    }

    private SupplementPick toSupplementPick(String tipo, Product p) {
        String img = p.imagenUrl() != null ? p.imagenUrl() : "";
        if (img.startsWith("//")) img = "https:" + img;
        return new SupplementPick(
                tipo,
                p.sitio() != null ? p.sitio() : "",
                p.nombre() != null ? p.nombre() : "",
                p.precio(),
                p.url() != null ? p.url() : "",
                img,
                p.marca() != null ? p.marca() : "");
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
        String g = p.genero() != null ? p.genero().trim() : "";
        if ("infantil".equalsIgnoreCase(g)) return false; // nunca en el armador, ni pidiendo unisex
        if (g.isEmpty()) return true;
        if ("unisex".equalsIgnoreCase(g)) return true;
        if (generoSolicitado == null || generoSolicitado.isBlank()) return true; // sin genero pedido: todo elegible
        if ("unisex".equalsIgnoreCase(generoSolicitado)) return true; // pedido unisex: todo elegible
        return g.equalsIgnoreCase(generoSolicitado);
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

    // ─── Budget Builder (MCKP) ───────────────────────────────────────────────────

    /** Maximum candidates considered per category during MCKP enumeration. */
    private static final int BUILDER_POOL_K = 20;

    /**
     * Backward-compatible 5-arg overload. Delegates to the 7-arg implementation
     * with no exclusions and MCKP mode. Keeps all existing callers and tests unchanged.
     */
    public OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, FeedbackModel feedback) {
        return armarPorCategorias(productos, categorias, presupuesto, genero, feedback,
                Set.of(), false);
    }

    /**
     * Assembles the globally-optimal product combination for the requested
     * category set within a hard budget ceiling using the Multi-Choice
     * Knapsack Problem (MCKP) algorithm, or the greedy fallback when
     * {@code greedy=true}.
     *
     * <p>Algorithm phases (MCKP):
     * <ol>
     *   <li>Build per-category raw pools: filter by categoria, gender, feedback
     *       exclusions, gymrat gate (torso/piernas only), and excluirUrls.</li>
     *   <li>Shuffle for variety: sort desc, take top-30, random-sample 20,
     *       re-sort desc (INVARIANT: pool.get(0) must be max score for B&B).</li>
     *   <li>Apply price filter (≤ presupuesto), cap at K=20.</li>
     *   <li>Recursive branch-and-bound enumeration.</li>
     *   <li>Build result; on no-fit, compute minimoBudgetNecesario.</li>
     * </ol>
     *
     * <p>INVARIANT: {@code result.totalEstimado() ≤ presupuesto} always holds.
     *
     * @param productos    in-memory catalog (from {@code ScraperService.lastResult})
     * @param categorias   requested canonical category names (deduplicated, ordered)
     * @param presupuesto  hard budget ceiling (must be &gt; 0)
     * @param genero       optional gender filter; null/blank = no filter
     * @param feedback     veto/boost model; null treated as empty
     * @param excluirUrls  URLs to exclude per-request (temp, not persisted)
     * @param greedy       when true, use greedy (best-per-category) instead of MCKP
     * @return optimal assignment or no-fit result
     */
    public OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy) {
        if (productos == null) productos = List.of();
        if (feedback == null) feedback = FeedbackModel.empty();
        if (excluirUrls == null) excluirUrls = Set.of();
        if (categorias == null || categorias.isEmpty()) {
            return new OutfitBuilderResult(List.of(), genero != null ? genero : "",
                    presupuesto, 0.0, false, List.of(), List.of(), null);
        }

        // Deduplicate; resolve each category to its sub-slot key via CATEGORIA_SUBSLOT.
        // torso-base / torso-outer are independent picks (layering); piernas and calzado
        // group all selected categories into one pick; accesorio splits into head/feet/body.
        List<String> cats = new ArrayList<>(new LinkedHashSet<>(categorias));
        final Set<String> excluirFinal = excluirUrls;

        Map<String, Set<String>> catsBySlot = new LinkedHashMap<>();
        for (String cat : cats) {
            String subslot = CATEGORIA_SUBSLOT.get(cat);
            if (subslot == null) continue;
            catsBySlot.computeIfAbsent(subslot, k -> new LinkedHashSet<>()).add(cat);
        }
        List<String> slotOrder = new ArrayList<>(catsBySlot.keySet());

        if (greedy) {
            return armarGreedy(productos, slotOrder, catsBySlot, presupuesto, genero, feedback, excluirFinal);
        }

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        List<String>        slotsVacios = new ArrayList<>();
        List<List<Product>> allPools    = new ArrayList<>();
        List<Boolean>       rawNonEmpty = new ArrayList<>();

        for (String slot : slotOrder) {
            Set<String> slotCats = catsBySlot.get(slot);
            List<Product> rawPool = productos.stream()
                    .filter(p -> slotCats.contains(p.categoria()))
                    .filter(p -> generoElegible(p, genero))
                    .filter(p -> !exclude.contains(FeedbackModel.keyOf(p)))
                    .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                    .filter(p -> !excluirFinal.contains(p.url()))
                    .filter(p -> {
                        if (slot.startsWith(SLOT_TORSO) || SLOT_PIERNAS.equals(slot)) {
                            return p.gymrat();
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            if (rawPool.isEmpty()) {
                slotsVacios.add(slot);
                allPools.add(List.of());
                rawNonEmpty.add(false);
                continue;
            }

            rawNonEmpty.add(true);

            List<Product> sortedRaw = rawPool.stream()
                    .sorted(Comparator.comparingDouble((Product p) -> -recommendationService.baseMlScore(p)))
                    .collect(Collectors.toList());

            // Take top-60 by score, shuffle to 30, filter by price — no re-sort after
            // shuffle so each regen sees a different candidate set (variety).
            List<Product> top60 = new ArrayList<>(sortedRaw.subList(0, Math.min(60, sortedRaw.size())));
            Collections.shuffle(top60, new Random());
            List<Product> filteredPool = top60.stream()
                    .filter(p -> p.precio() <= presupuesto)
                    .limit(BUILDER_POOL_K)
                    .collect(Collectors.toList());

            allPools.add(filteredPool);
        }

        MckpSolver solver = new MckpSolver(recommendationService, allPools, presupuesto);
        solver.solve(0, 0.0, 0.0, new Product[slotOrder.size()]);

        Product[] bestSolution = solver.best;
        Set<String> slotsInSolution = new HashSet<>();
        List<SlotPick> slots = new ArrayList<>();

        for (int i = 0; i < slotOrder.size(); i++) {
            Product p = bestSolution[i];
            if (p != null) {
                slots.add(toSlotPick(slotOrder.get(i), p));
                slotsInSolution.add(slotOrder.get(i));
            }
        }

        List<String> slotsSinPresupuesto = new ArrayList<>();
        for (int i = 0; i < slotOrder.size(); i++) {
            String slot = slotOrder.get(i);
            if (rawNonEmpty.get(i) && !slotsInSolution.contains(slot)) {
                slotsSinPresupuesto.add(slot);
            }
        }

        boolean noCumplePresupuesto = !slotsSinPresupuesto.isEmpty();
        double totalEstimado = slots.stream().mapToDouble(SlotPick::precio).sum();
        String generoResultado = genero != null ? genero : "";

        Double minimoBudgetNecesario = null;
        if (slots.isEmpty()) {
            minimoBudgetNecesario = calcularMinimoBudget(
                    productos, slotOrder, catsBySlot, genero, feedback, excluirFinal);
        }

        return new OutfitBuilderResult(slots, generoResultado, presupuesto,
                totalEstimado, noCumplePresupuesto, slotsVacios, slotsSinPresupuesto,
                minimoBudgetNecesario);
    }

    /**
     * Greedy outfit assembler: for each category in order, picks the highest
     * baseMlScore candidate where {@code precio ≤ remainingBudget}. Hard budget
     * is always enforced (never exceeded). Categories with no affordable candidate
     * are skipped.
     *
     * <p>Applies the same gymrat gate as the MCKP path so all three paths
     * (MCKP, greedy, calcularMinimoBudget) use identical eligibility rules.
     */
    private OutfitBuilderResult armarGreedy(
            List<Product> productos, List<String> slotOrder,
            Map<String, Set<String>> catsBySlot, double presupuesto,
            String genero, FeedbackModel feedback, Set<String> excluirUrls) {
        if (productos == null) productos = List.of();

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        List<SlotPick> slots = new ArrayList<>();
        double runningTotal  = 0.0;

        for (String slot : slotOrder) {
            Set<String> slotCats = catsBySlot.get(slot);
            List<Product> sorted = productos.stream()
                    .filter(p -> slotCats.contains(p.categoria()))
                    .filter(p -> generoElegible(p, genero))
                    .filter(p -> !exclude.contains(FeedbackModel.keyOf(p)))
                    .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                    .filter(p -> !excluirUrls.contains(p.url()))
                    .filter(p -> {
                        if (slot.startsWith(SLOT_TORSO) || SLOT_PIERNAS.equals(slot)) {
                            return p.gymrat();
                        }
                        return true;
                    })
                    .sorted(Comparator.comparingDouble((Product p) -> -recommendationService.baseMlScore(p)))
                    .collect(Collectors.toList());

            // Shuffle top-30 by score for variety across re-rolls (same pattern as MCKP pool).
            // Without this the greedy is deterministic and always returns the identical outfit.
            List<Product> pool = new ArrayList<>(sorted.subList(0, Math.min(30, sorted.size())));
            Collections.shuffle(pool, new Random());

            final double remaining = presupuesto - runningTotal;
            Optional<Product> pick = pool.stream()
                    .filter(p -> p.precio() <= remaining)
                    .findFirst();

            if (pick.isPresent()) {
                Product chosen = pick.get();
                slots.add(toSlotPick(slot, chosen));
                runningTotal += chosen.precio();
            }
        }

        String generoResultado = genero != null ? genero : "";
        double totalEstimado   = slots.stream().mapToDouble(SlotPick::precio).sum();
        return new OutfitBuilderResult(slots, generoResultado, presupuesto,
                totalEstimado, false, List.of(), List.of(), null);
    }

    /**
     * Returns the minimum budget needed to assemble one product per category,
     * using the same eligibility filters as the MCKP pool (gymrat gate, gender,
     * feedback, excluirUrls) but ignoring price. Returns null if any category
     * has zero eligible products (catalog gap).
     *
     * <p>Used to populate {@link OutfitBuilderResult#minimoBudgetNecesario()} on
     * no-fit responses so the frontend can show "Necesitás al menos $X más".
     */
    private Double calcularMinimoBudget(
            List<Product> productos, List<String> slotOrder,
            Map<String, Set<String>> catsBySlot, String genero,
            FeedbackModel feedback, Set<String> excluirUrls) {
        if (productos == null) return null;

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        double total = 0.0;
        for (String slot : slotOrder) {
            Set<String> slotCats = catsBySlot.get(slot);
            OptionalDouble minPrecio = productos.stream()
                    .filter(p -> slotCats.contains(p.categoria()))
                    .filter(p -> generoElegible(p, genero))
                    .filter(p -> !exclude.contains(FeedbackModel.keyOf(p)))
                    .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                    .filter(p -> !excluirUrls.contains(p.url()))
                    .filter(p -> {
                        if (slot.startsWith(SLOT_TORSO) || SLOT_PIERNAS.equals(slot)) {
                            return p.gymrat();
                        }
                        return true;
                    })
                    .mapToDouble(Product::precio)
                    .min();

            if (minPrecio.isEmpty()) {
                return null; // catalog gap — no eligible product for this slot
            }
            total += minPrecio.getAsDouble();
        }
        return total;
    }

    /**
     * Multi-Choice Knapsack Problem solver.
     * One item is chosen from each category group (or the group is skipped),
     * subject to {@code sum(prices) ≤ presupuesto}. Maximizes total
     * {@code baseMlScore} across all selected items.
     *
     * <p>Branch-and-bound pruning: at each node, the upper bound is the
     * current running score plus the sum of the best (index-0) score for
     * each remaining category pool. If this upper bound cannot beat the
     * current best solution, the branch is pruned.
     */
    private static final class MckpSolver {
        private final RecommendationService recService;
        private final List<List<Product>>   pools;
        private final double                presupuesto;
        private final double[]              maxScorePerCat;

        Product[] best;
        double    bestScore = Double.NEGATIVE_INFINITY;

        MckpSolver(RecommendationService recService,
                   List<List<Product>> pools, double presupuesto) {
            this.recService  = recService;
            this.pools       = pools;
            this.presupuesto = presupuesto;
            int n = pools.size();
            this.best           = new Product[n];
            this.maxScorePerCat = new double[n];
            for (int i = 0; i < n; i++) {
                List<Product> pool = pools.get(i);
                // Pool is sorted desc by baseMlScore; first element is the max
                maxScorePerCat[i] = pool.isEmpty()
                        ? 0.0 : pool.stream()
                            .mapToDouble(recService::baseMlScore)
                            .max().orElse(0.0);
            }
        }

        void solve(int idx, double total, double score, Product[] current) {
            if (idx == pools.size()) {
                if (score > bestScore) {
                    bestScore = score;
                    System.arraycopy(current, 0, best, 0, current.length);
                }
                return;
            }

            // Branch-and-bound: if max possible score from here ≤ bestScore, prune
            double upperBound = score;
            for (int i = idx; i < pools.size(); i++) upperBound += maxScorePerCat[i];
            if (upperBound <= bestScore) return;

            // Option A: skip this category (partial outfit)
            current[idx] = null;
            solve(idx + 1, total, score, current);

            // Option B: pick an affordable candidate
            double remaining = presupuesto - total;
            for (Product p : pools.get(idx)) {
                if (p.precio() > remaining) continue;
                current[idx] = p;
                solve(idx + 1, total + p.precio(),
                      score + recService.baseMlScore(p), current);
            }
            current[idx] = null; // backtrack
        }
    }

    private SlotPick toSlotPick(String slot, Product p) {
        String img = p.imagenUrl() != null ? p.imagenUrl() : "";
        if (img.startsWith("//")) img = "https:" + img;
        return new SlotPick(
                slot,
                p.sitio() != null ? p.sitio() : "",
                p.nombre() != null ? p.nombre() : "",
                p.precio(),
                p.url() != null ? p.url() : "",
                img,
                p.categoria() != null ? p.categoria() : "",
                p.marca() != null ? p.marca() : "");
    }
}
