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

    /** Resultado completo de armar() — outfit con slots, genero usado y flag partial. */
    public record Outfit(List<SlotPick> slots, String genero, boolean partial) {
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
            new SubtipoSuplemento("Proteína", new String[]{"proteina", "protein", "whey", "isolate", "concentrate"}),
            new SubtipoSuplemento("Creatina", new String[]{"creatina", "creatine", "monohidrato"}),
            new SubtipoSuplemento("Quemador", new String[]{"quemador", "fat burner", "termogenico", "carnitina", "cla "}),
            new SubtipoSuplemento("Magnesio", new String[]{"magnesio", "magnesium", "citrato de magnesio"})
    );

    /**
     * Orden de preferencia de marca para el combo de suplementos (confirmado por
     * el usuario): ENA y STAR ya tienen stock real en el catálogo; BCC ("La Roja")
     * no tiene productos hoy, pero queda en la lista para entrar sola el día que
     * se scrapee esa marca, sin tocar este código de nuevo.
     */
    private static final List<String> SUPLEMENTO_MARCA_PRIORIDAD = List.of("ENA", "STAR", "BCC");

    /**
     * Combo de suplementos (Proteína/Creatina/Quemador/Magnesio) a mostrar siempre
     * junto al outfit, independiente de género/estilo — best-effort por subtipo
     * (subtipo sin candidatos se omite del combo, mismo criterio que el accesorio
     * del armador de outfits).
     */
    public List<SupplementPick> armarComboSuplementos(List<Product> productos) {
        if (productos == null) productos = List.of();
        List<Product> suplementos = productos.stream()
                .filter(p -> "Suplemento".equals(p.categoria()))
                .collect(Collectors.toList());

        List<SupplementPick> combo = new ArrayList<>();
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            List<Product> candidatos = suplementos.stream()
                    .filter(p -> matchesSubtipo(p.nombre(), subtipo.keywords()))
                    .collect(Collectors.toList());
            if (candidatos.isEmpty()) continue;
            Product elegido = elegirPorMarcaPrioridad(candidatos);
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
