package ar.scraper.web;

import ar.scraper.model.Product;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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
     * Regla de elegibilidad por estilo (ADR-3 en design.md de
     * outfit-recommendation-quality): un whitelist de categorias de calzado nullable —
     * null significa "sin restricción de estilo, usar la taxonomía base"
     * (esCalzadoBase). Solo calzado está restringido para Gym v1; torso/piernas/accesorio
     * quedan sin restricción adicional por estilo (el campo es la extensión point, no
     * los valores futuros — agregar p.ej. torsoWhitelist más adelante no requiere
     * tocar armar()/slotDe()).
     */
    private record StyleRule(Set<String> calzadoWhitelist /* nullable */) { }

    private static final Map<String, StyleRule> STYLE_RULES = Map.of(
            "gym", new StyleRule(Set.of(
                    "Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento",
                    "Zapatilla Skate", "Zapatilla Urbana", "Sneaker"))
            // Botines/Borcego/Botas/Ojotas intencionalmente EXCLUIDOS para Gym.
    );
    private static final StyleRule DEFAULT_STYLE_RULE = new StyleRule(null); // sin restricción

    /**
     * Veto global (ADR-2 de outfit-per-item-feedback): categorias acá NUNCA son
     * elegibles para el slot calzado, bajo NINGÚN estilo — ni siquiera
     * DEFAULT_STYLE_RULE (whitelist null). Chequeado en slotDe() ANTES del gate
     * de estilo, así que es independiente de STYLE_RULES.
     * Borcego/Botas/Ojotas NO están acá — siguen gobernados solo por el
     * whitelist Gym-only de STYLE_RULES.
     */
    private static final Set<String> CALZADO_VETADO = Set.of("Botines");

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
                "Botines", "Borcego", "Botas", "Ojotas", "Sneaker")) {
            m.put(cat, SLOT_CALZADO);
        }
        for (String cat : List.of(
                "Mochila", "Bolso", "Riñonera", "Billetera", "Cinturón", "Bufanda",
                "Guantes", "Gorro", "Gorra", "Lentes", "Medias")) {
            m.put(cat, SLOT_ACCESORIO);
        }
        return Collections.unmodifiableMap(m);
    }

    /** Resultado de un slot individual dentro de un outfit generado. */
    public record SlotPick(
            String slot, String sitio, String nombre, double precio,
            String url, String img, String categoria, String marca) {
    }

    /** Resultado completo de armar() — outfit con slots, genero usado y flag partial. */
    public record Outfit(List<SlotPick> slots, String genero, boolean partial) {
    }

    /**
     * Modelo de feedback (ADR-1/ADR-2 en design.md de outfit-recommendation-quality):
     * exclude = pares marca|categoria con al menos un dislike (veto duro, permanente);
     * boostLikeCount = cantidad de likes por par marca|categoria (folding en
     * weightedRandomPick, Fase 2 — Task 2.6; no usado todavía en esta fase).
     * Construido por ApiController.buildFeedbackModel() a partir de
     * DatabaseService.obtenerOutfitFeedback() + el catálogo vivo (OutfitService
     * permanece DB-agnostic, ADR-3 de outfit-builder).
     */
    public record FeedbackModel(Set<String> exclude, Map<String, Integer> boostLikeCount) {
        public static FeedbackModel empty() {
            return new FeedbackModel(Set.of(), Map.of());
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
        if (CALZADO_VETADO.contains(cat)) return null; // global, style-independent
        if (esCalzadoBase(cat)) {
            return esCalzadoElegible(rule, cat) ? SLOT_CALZADO : null;
        }
        return CATEGORIA_SLOT.get(cat);
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
     */
    private boolean generoElegible(Product p, String generoSolicitado) {
        String g = p.genero() != null ? p.genero().trim() : "";
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
     * Armar un outfit para el genero y estilo solicitados, con feedback de
     * usuario aplicado (ADR-1/ADR-2/ADR-3 de design.md, outfit-recommendation-quality).
     * estilo resuelve la StyleRule activa vía STYLE_RULES (default: sin restricción
     * de estilo) — agregar un estilo nuevo no requiere tocar este método, solo
     * STYLE_RULES (spec "New style can be added without rewriting armar()").
     */
    public Outfit armar(List<Product> productos, String generoSolicitado, String estilo, FeedbackModel feedback) {
        if (productos == null) productos = List.of();
        if (feedback == null) feedback = FeedbackModel.empty();
        Set<String> exclude = feedback.exclude();
        StyleRule rule = STYLE_RULES.getOrDefault(estilo, DEFAULT_STYLE_RULE);

        // 1. Particionar por slot, solo gymrat (torso/piernas) o calzado elegible
        //    bajo la StyleRule activa.
        //    Hard exclude (ADR-2): se descarta cualquier producto cuyo marca|categoria
        //    esté en feedback.exclude() ANTES de que corra el fallback de 3 pasos.
        Map<String, List<Product>> bySlot = new HashMap<>();
        for (Product p : productos) {
            String slot = slotDe(p, rule);
            if (slot == null) continue;
            if (exclude.contains(FeedbackModel.keyOf(p))) continue;
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

        for (String slot : SLOTS_REQUERIDOS) {
            List<Product> base = bySlot.getOrDefault(slot, List.of());

            // Paso 0: genero + banda de precio
            List<Product> cands = filtrar(base, generoSolicitado, band[0], band[1]);

            // Paso 1: relajar banda de precio (mantener genero)
            if (cands.isEmpty()) {
                cands = filtrar(base, generoSolicitado, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            }

            // Paso 2: relajar genero a unisex-only (mantener banda completa)
            if (cands.isEmpty()) {
                cands = filtrar(base, "unisex", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            }

            // Paso 3: sin candidatos tras ambas relajaciones → partial, sin fabricar producto
            if (cands.isEmpty()) {
                partial = true;
                continue;
            }

            Product elegido = weightedRandomPick(cands, band, feedback.boostLikeCount());
            picks.put(slot, toSlotPick(slot, elegido));
        }

        // Accesorio: best-effort, sin fallback (ADR confirmado en design.md / spec).
        List<Product> accesorios = bySlot.getOrDefault(SLOT_ACCESORIO, List.of());
        List<Product> accesoriosElegibles = filtrar(accesorios, generoSolicitado,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        if (!accesoriosElegibles.isEmpty()) {
            Product accesorio = weightedRandomPick(accesoriosElegibles, band, feedback.boostLikeCount());
            picks.put(SLOT_ACCESORIO, toSlotPick(SLOT_ACCESORIO, accesorio));
        }

        List<SlotPick> ordenados = new ArrayList<>();
        for (String slot : List.of(SLOT_TORSO, SLOT_PIERNAS, SLOT_CALZADO, SLOT_ACCESORIO)) {
            SlotPick pick = picks.get(slot);
            if (pick != null) ordenados.add(pick);
        }

        String generoResultado = (generoSolicitado != null && !generoSolicitado.isBlank())
                ? generoSolicitado : "unisex";
        return new Outfit(ordenados, generoResultado, partial);
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
     */
    private Product weightedRandomPick(List<Product> candidatos, double[] band,
                                        Map<String, Integer> boostLikeCount) {
        if (candidatos.size() == 1) return candidatos.get(0);

        double centro = (Double.isFinite(band[0]) && Double.isFinite(band[1]))
                ? (band[0] + band[1]) / 2.0
                : candidatos.stream().mapToDouble(Product::precio).average().orElse(0);

        double[] pesos = new double[candidatos.size()];
        double totalPeso = 0;
        for (int i = 0; i < candidatos.size(); i++) {
            Product c = candidatos.get(i);
            double distancia = Math.abs(c.precio() - centro);
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
