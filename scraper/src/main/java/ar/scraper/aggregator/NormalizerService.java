package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ar.scraper.aggregator.normalize.CategoryGroups.esCalzado;
import static ar.scraper.aggregator.normalize.CategoryGroups.esCategoriaSuplemento;
import static ar.scraper.aggregator.normalize.CategoryGroups.esIndumentariaOCalzado;
import static ar.scraper.aggregator.normalize.GarmentTaxonomy.*;
import static ar.scraper.aggregator.normalize.SiteClassification.*;

/**
 * Normalización profunda post-scraping.
 *
 * Principio de diseño:
 *   Las reglas más ESPECÍFICAS van primero.
 *   "Zapatilla Running" antes que "Zapatilla".
 *   "Buzo" y "Sweater" son categorías DISTINTAS.
 *   El nombre del producto tiene prioridad sobre la categoría cruda del sitio.
 *
 * <p>Keyword taxonomies ({@code KW_*}), category/site predicate holders, and
 * the non-textile guard live in {@code ar.scraper.aggregator.normalize}
 * (Work Unit 3 of the aggregator SOLID modularization) and are consumed here
 * via static import — pure relocation, no behavior change. Pack-quantity
 * detection is delegated to {@link PackQuantityDetector} (Work Unit 4) and
 * category classification to {@link CategoryClassifier} (Work Unit 5). This
 * class still owns gender/brand/size resolution and orchestration; those
 * collaborators are extracted in later work units.</p>
 */
@Component
public class NormalizerService {

    // Collaborators are field-initialized (not constructor-injected) until
    // Work Unit 8 gives NormalizerService a full constructor over all 12
    // normalize collaborators — see design ADR-2. Stateless, so behavior is
    // identical either way.
    private final PackQuantityDetector packQuantityDetector = new PackQuantityDetector();
    private final CategoryClassifier categoryClassifier = new CategoryClassifier();

    // ══════════════════════════════════════════════════════════════════
    // MARCAS conocidas en Argentina
    // ══════════════════════════════════════════════════════════════════

    private static final List<String> MARCAS = List.of(
        "Nike","Adidas","Puma","Reebok","New Balance","Asics","Saucony","Brooks",
        "Hoka","On Running","Salomon","Mizuno","Under Armour","Fila","Umbro",
        "Vans","Converse","DC","Etnies","Volcom","Quiksilver","Billabong",
        "The North Face","Columbia","Patagonia","Timberland","Merrell",
        "Topper","Flecha","Jaguar","Gola","Penalty","Olympikus",
        "Lacoste","Tommy","Calvin Klein","Levi's","Levis","Wrangler",
        "Champion","Kappa","Ellesse","Le Coq Sportif","Fred Perry",
        "Caterpillar","Keen","Palladium","Crocs","Birkenstock",
        "Bulks","Fuark","Harvey Willys","Harvey"
    );

    // Word-boundary patterns (no substring matches) — evita falsos positivos
    // como "DC" matcheando dentro de "Hardcore" o "HDCP" (ver bug category-brand-quality-fixes).
    private static final List<Pattern> MARCA_PATTERNS = MARCAS.stream()
            .map(m -> Pattern.compile("\\b" + Pattern.quote(m.toLowerCase()) + "\\b"))
            .collect(Collectors.toList());

    // Talles
    // ══════════════════════════════════════════════════════════════════

    private static final Map<String, String> TALLE_MAP = new LinkedHashMap<>();
    static {
        TALLE_MAP.put("extra small", "XS"); TALLE_MAP.put("extrasmall","XS"); TALLE_MAP.put("xxs","XXS");
        TALLE_MAP.put("small","S");   TALLE_MAP.put("chico","S");   TALLE_MAP.put("ch","S");
        TALLE_MAP.put("medium","M");  TALLE_MAP.put("mediano","M"); TALLE_MAP.put("med","M");
        TALLE_MAP.put("large","L");   TALLE_MAP.put("grande","L");  TALLE_MAP.put("gr","L");
        TALLE_MAP.put("extra large","XL"); TALLE_MAP.put("extralarge","XL");
        TALLE_MAP.put("xxlarge","XXL"); TALLE_MAP.put("extra extra large","XXL");
        TALLE_MAP.put("xxxlarge","XXXL"); TALLE_MAP.put("3xl","XXXL"); TALLE_MAP.put("3 xl","XXXL");
        TALLE_MAP.put("talle unico","Único"); TALLE_MAP.put("unico","Único"); TALLE_MAP.put("unique","Único");
        TALLE_MAP.put("default title","");
    }

    // ══════════════════════════════════════════════════════════════════
    // Normalización principal
    // ══════════════════════════════════════════════════════════════════

    public List<Product> normalizar(List<Product> productos) {
        return productos.stream()
                .map(this::normalizarProducto)
                .collect(Collectors.toList());
    }

    private Product normalizarProducto(Product p) {
        String nombre = p.nombre() != null ? p.nombre() : "";
        String cat    = categoryClassifier.normalizarCategoria(p.categoria(), nombre);
        String genero = normalizarGenero(p.genero(), nombre, cat);
        List<String> talles = normalizarTalles(p.talles());
        String marca  = (p.marca() == null || p.marca().isBlank())
                        ? extraerMarca(nombre, p.sitio())
                        : p.marca();

        // Determinar rubro: forzar por sitio, luego por categoría, luego usar existente
        String sitioKey = sitioKey(p.sitio());
        boolean catEsTextil = esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = esCategoriaSuplemento(cat);

        String rubro;
        if (TECH_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]","")))
                && !catEsTextil) {
            rubro = "tecnologia";
        } else if (catEsSuppl) {
            rubro = "suplementos";
        } else if (SUPPL_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]","")))
                   && !catEsTextil) {
            rubro = "suplementos";
        } else if (catEsTextil) {
            rubro = "indumentaria";
        } else if (p.rubro() != null && !p.rubro().isBlank()) {
            rubro = p.rubro();
        } else {
            rubro = "indumentaria";
        }

        boolean gymrat       = esGymrat(nombre, sitioKey, cat, rubro, marca);
        boolean marcaPremium = SITIOS_PREMIUM.contains(sitioKey);
        int cantidadUnidades = packQuantityDetector.detectar(nombre, cat);
        String subCategoria  = resolverSubCategoria(nombre, cat);

        return new Product(p.sitio(), nombre, p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), cat, genero, talles,
                p.ml(), marca, rubro, gymrat, marcaPremium, p.senal(),
                p.finan(), cantidadUnidades, subCategoria);
    }

    /**
     * Tag transversal "gymrat": ropa pensada para entrenar.
     * Aditivo — NO altera categoria ni rubro.
     *
     * Reglas (OR), con guard de calzado:
     *   1) keyword de KW_TRAINING_ROPA en el nombre, O
     *   2) marca en GYM_MARCAS (nike, adidas, puma, champion, under armour, reebok), O
     *   3) sitio en GYM_SITIOS (bulks, fuark), O
     *   4) sitio == entreno Y el producto es indumentaria (no Suplemento/Alimentos)
     * Guard duro: si la categoria es calzado → false (gymrat es ROPA, no calzado).
     */
    private boolean esGymrat(String nombre, String sitioKey, String cat, String rubro, String marca) {
        if (esCalzado(cat)) return false;
        if ("suplementos".equals(rubro) || "Suplemento".equals(cat) || "Alimentos".equals(cat))
            return false;

        String n = (nombre != null ? nombre : "").toLowerCase()
            .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
            .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
            .replaceAll("[úùü]","u").replaceAll("[ñ]","n");

        if (anyMatch(n, KW_TRAINING_ROPA)) return true;
        if (marca != null && GYM_MARCAS.contains(marca.trim().toLowerCase())) return true;
        if (GYM_SITIOS.stream().anyMatch(sitioKey::contains)) return true;
        if (sitioKey.contains("entreno")) return true;
        return false;
    }

    /**
     * Generic keyword-containment helper for {@link #esGymrat}. Category
     * classification's own copy of this helper moved to
     * {@link CategoryClassifier} (Work Unit 5) — this one stays here
     * temporarily until {@code esGymrat} is extracted into {@code GymratTagger}
     * (Work Unit 8), a trivial 3-line loop, not part of the taxonomy-drift
     * surface ADR-1 addresses.
     */
    private boolean anyMatch(String text, String[] keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    // ──────────────────────────────────────────────────────────────────
    // Subcategoría — 3-tier activity/sport-based classifier
    // Design: subcategoria-field
    // ──────────────────────────────────────────────────────────────────

    /**
     * Tier-1 category-specific subcategory rules.
     *
     * <p>Each category maps to an ordered list of {@code String[]} entries.
     * Entry format: {@code entry[0]} = subcategory output value;
     * {@code entry[1..n]} = keywords to match (accent/case-insensitive,
     * space-padded for word-boundary safety). An entry with only one element
     * (no keywords) acts as an unconditional default for that category.
     * First match wins; entries are evaluated in declaration order.</p>
     */
    private static final Map<String, List<String[]>> SUBCATEG_TIER1;
    static {
        SUBCATEG_TIER1 = new LinkedHashMap<>();
        // Gorro: specific keywords first; last entry = default "invierno"
        SUBCATEG_TIER1.put("Gorro", Arrays.<String[]>asList(
            new String[]{"natación",   "natacion", "swimming", "swim cap", "pileta"},
            new String[]{"ski",        "ski", "snowboard", "snowboarding", "nieve"},
            new String[]{"invierno"}   // default for Gorro — no keywords, always matches
        ));
        SUBCATEG_TIER1.put("Malla", Arrays.<String[]>asList(
            new String[]{"bikini",     "bikini", "triangulo", "triangular"},
            new String[]{"entera",     "one piece", "entera", "enteriza"},
            new String[]{"tankini",    "tankini"}
        ));
        SUBCATEG_TIER1.put("Short", Arrays.<String[]>asList(
            new String[]{"natación",   "natacion", "bano", "swim"},
            new String[]{"cargo",      "cargo"},
            new String[]{"gym",        "gym", "training", "entrenamiento", "fitness"}
        ));
        SUBCATEG_TIER1.put("Campera", Arrays.<String[]>asList(
            new String[]{"outdoor",      "hiking", "trekking", "montana", "outdoor", "trail"},
            new String[]{"running",      "running", "runner"},
            new String[]{"polar",        "polar", "fleece"},
            new String[]{"rompevientos", "rompevientos", "cortavientos", "cortaviento", "wind"}
        ));
        SUBCATEG_TIER1.put("Conjunto", Arrays.<String[]>asList(
            new String[]{"deportivo",  "deportivo", "gym", "training", "entrenamiento",
                                       "running", "fitness", "sport"},
            new String[]{"interior",   "interior", "intimo", "lenceria"},
            new String[]{"baño",       "bano", "swim", "natacion"}
        ));
        SUBCATEG_TIER1.put("Calza", Arrays.<String[]>asList(
            new String[]{"running",    "running", "runner"},
            new String[]{"ciclista",   "ciclista", "cycling", "ciclismo"},
            new String[]{"térmica",    "termica"}
        ));
        SUBCATEG_TIER1.put("Musculosa", Arrays.<String[]>asList(
            new String[]{"deportiva",  "deportiva", "gym", "training", "entrenamiento", "fitness", "sport"}
        ));
        SUBCATEG_TIER1.put("Calzoncillos", Arrays.<String[]>asList(
            new String[]{"boxer",  "boxer"},
            new String[]{"slip",   "slip"},
            new String[]{"brief",  "brief"}
        ));
        SUBCATEG_TIER1.put("Corpino", Arrays.<String[]>asList(
            new String[]{"deportivo",   "deportivo", "training", "gym", "sport", "fitness"},
            new String[]{"push-up",     "push up", "pushup", "push-up"},
            new String[]{"triangular",  "triangular", "triangulo"},
            new String[]{"bralette",    "bralette"}
        ));
        SUBCATEG_TIER1.put("Medias", Arrays.<String[]>asList(
            new String[]{"deportivas",  "deportivas", "sport", "running"},
            new String[]{"compresión",  "compresion"},
            new String[]{"tobilleras",  "tobilleras", "tobillo"}
        ));
        SUBCATEG_TIER1.put("Buzo", Arrays.<String[]>asList(
            new String[]{"hoodie",  "hoodie", "capucha", "canguro"}
        ));
        SUBCATEG_TIER1.put("Bolso", Arrays.<String[]>asList(
            new String[]{"tote",      "tote"},
            new String[]{"cartera",   "mano", "cartera"},
            new String[]{"mensajero", "mensajero", "crossbody"}
        ));
        SUBCATEG_TIER1.put("Mochila", Arrays.<String[]>asList(
            new String[]{"trekking",  "trekking", "hiking", "outdoor"},
            new String[]{"deportiva", "deportiva", "gym", "sport"}
        ));
    }

    /**
     * Tier-2 transversal sport keywords.
     *
     * <p>Evaluated in order after tier-1 produces no match. First entry whose
     * keyword set contains a word from the product {@code nombre} wins.
     * The {@code "running"} entry is guarded: skipped when {@code categoria}
     * already contains the word "running" (accent-insensitive), to avoid
     * redundant labelling for "Zapatilla Running" products.</p>
     *
     * <p>Entry format: {@code entry[0]} = subcategory value; {@code entry[1..n]}
     * = keywords (post-normalization, no accents).</p>
     */
    private static final List<String[]> SUBCATEG_TIER2 = Arrays.<String[]>asList(
        new String[]{"natación",   "natacion", "swimming", "swim cap"},
        new String[]{"hockey",     "hockey"},
        new String[]{"fútbol",     "futbol", "football"},
        new String[]{"pádel",      "padel"},
        new String[]{"tenis",      "tenis", "tennis"},
        new String[]{"running",    "running", "runner"},    // guard: skipped if categoria contains "running"
        new String[]{"vóley",      "voley", "volleyball"},
        new String[]{"básquet",    "basket", "basketball"},
        new String[]{"ciclismo",      "ciclismo", "cycling"},
        new String[]{"escalada",      "escalada", "climbing"},
        new String[]{"snowboarding",  "snowboard", "snowboarding"}
    );

    /**
     * Resolves the subcategory for a product via a 3-tier chain.
     *
     * <p><b>Tier 1</b>: category-specific keyword match (see {@link #SUBCATEG_TIER1}).
     * For {@code "Gorro"}, a catch-all default entry always fires when no keyword
     * matches, returning {@code "invierno"}. For all other categories, unmatched
     * keywords fall through to tier 2.</p>
     *
     * <p><b>Tier 2</b>: transversal sport keyword scan of {@code nombre}.
     * First match in {@link #SUBCATEG_TIER2} wins. The {@code "running"} entry
     * is skipped when {@code categoria} already contains the word "running".</p>
     *
     * <p><b>Tier 3</b>: {@code ""} — never {@code null}.</p>
     *
     * <p>All comparison is accent/case-insensitive (via {@link #normalizarAcentos}).
     * Word-boundary safety uses space-padding: the normalized {@code nombre}
     * is wrapped in spaces and each keyword is also wrapped, so embedded
     * tokens like "espadela" never match the keyword "padel".</p>
     *
     * @param nombre   raw product name (may have accents and mixed case)
     * @param categoria resolved category from {@code normalizarCategoria}
     * @return non-null subcategory string; {@code ""} when no tier matches
     */
    String resolverSubCategoria(String nombre, String categoria) {
        if (nombre == null || nombre.isBlank() || categoria == null) return "";
        // Space-padded, accent-normalized name — enables safe word-boundary matching
        String n = " " + normalizarAcentos(nombre) + " ";

        // Tier 1: category-specific rules
        List<String[]> tier1 = SUBCATEG_TIER1.get(categoria);
        if (tier1 != null) {
            for (String[] entry : tier1) {
                if (entry.length == 1) {
                    // Default entry (no keywords) — unconditional match for this category
                    return entry[0];
                }
                for (int i = 1; i < entry.length; i++) {
                    if (n.contains(" " + entry[i] + " ")) {
                        return entry[0];
                    }
                }
            }
        }

        // Tier 2: transversal sport scan
        String catNorm = normalizarAcentos(categoria);
        for (String[] entry : SUBCATEG_TIER2) {
            String subcat = entry[0];
            // Running guard: skip when categoria already contains "running"
            if ("running".equals(subcat) && catNorm.contains("running")) continue;
            for (int i = 1; i < entry.length; i++) {
                if (n.contains(" " + entry[i] + " ")) {
                    return subcat;
                }
            }
        }

        // Tier 3: default
        return "";
    }

    // ──────────────────────────────────────────────────────────────────
    // Género
    // ──────────────────────────────────────────────────────────────────

    // Categorias cuya naturaleza es predominantemente femenina en este catálogo
    // (confirmado por revisión del usuario). Cuando una de estas categorias aparece
    // SIN señal masculina explícita en el nombre del producto, el género se fuerza a
    // "mujer" antes de que el combined-check (que incluye raw VTEX) lo pise.
    // Ver outfits-v2 design — R1/T1.
    private static final Set<String> FEMININE_CODED_CATEGORIES =
            Set.of("Calza", "Pollera", "Vestido", "Enterito", "Corpino", "Malla");

    String normalizarGenero(String raw, String nombre, String categoria) {
        String nombreNorm = normalizarAcentos(nombre != null ? nombre : "");
        String combined = normalizarAcentos((raw != null ? raw : "") + " " + (nombre != null ? nombre : ""));

        // Infantil primero: "niños"/"niñas" no debe perderse contra ningún otro
        // match (gym armador los excluye explícitamente — no son adultos).
        if (combined.contains("nino") || combined.contains("nina") ||
            combined.contains("kids") || combined.contains("infantil") ||
            combined.contains("bebe")) return "infantil";

        // Señal explícita "de hombre"/"de mujer" en el NOMBRE del producto gana
        // sobre un spec de género del sitio (raw) que puede estar mal taggeado
        // a nivel catálogo — bug encontrado en vivo: "Calza Nike One De Mujer"
        // (Sporting) traía raw="Hombre" del spec VTEX y el combined check de
        // abajo (que mira raw+nombre con "hombre" primero) lo pisaba, mostrando
        // una calza de mujer en outfits de hombre.
        if (nombreNorm.contains("de mujer") || nombreNorm.contains("para mujer")) return "mujer";
        if (nombreNorm.contains("de hombre") || nombreNorm.contains("para hombre")) return "hombre";

        // Feminine-coded category override: si la categoría es inherentemente femenina
        // Y el nombre del producto no tiene señal masculina explícita, forzar "mujer"
        // ANTES de que combined.contains("hombre") lo pise con el raw VTEX.
        // Cubre: Calza, Pollera, Vestido, Enterito, Corpino, Malla.
        boolean hasExplicitMascSignal = nombreNorm.contains("de hombre")
                || nombreNorm.contains("para hombre")
                || nombreNorm.contains(" hombre")
                || nombreNorm.contains("masculino")
                || nombreNorm.contains("caballero");
        if (FEMININE_CODED_CATEGORIES.contains(categoria) && !hasExplicitMascSignal) {
            return "mujer";
        }

        if (combined.contains("hombre") || combined.contains("masculino") ||
            combined.contains(" men")   || combined.contains("male")      ||
            combined.contains("caballero") || combined.contains("varones")) return "hombre";

        if (combined.contains("mujer")  || combined.contains("femenino") ||
            combined.contains("women")  || combined.contains("female")   ||
            combined.contains("dama")   || combined.contains("damas")) return "mujer";

        if (combined.contains("unisex") || combined.contains("neutro")) return "unisex";

        // Inferir por nombre de producto (modelos icónicos)
        if (combined.contains("wmns") || combined.contains("w ") ||
            combined.contains(" w)")) return "mujer";

        if (raw != null && !raw.isBlank()) return raw.trim().toLowerCase();

        // Sin ninguna señal de género (ni nombre ni spec del sitio): Calza es,
        // en este catálogo, predominantemente de mujer (80 mujer vs. un puñado
        // de hombre, todas estas últimas con tag explícito) — confirmado por el
        // usuario tras ver calzas sin género coladas en outfits de hombre.
        if ("Calza".equals(categoria)) return "mujer";

        return "";
    }

    private String normalizarAcentos(String s) {
        return s.toLowerCase()
                .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
    }

    // ──────────────────────────────────────────────────────────────────
    // Marca
    // ──────────────────────────────────────────────────────────────────

    public String extraerMarca(String nombre, String sitio) {
        if (nombre == null || nombre.isBlank()) return "";
        String lower = nombre.toLowerCase();

        for (int i = 0; i < MARCAS.size(); i++) {
            if (MARCA_PATTERNS.get(i).matcher(lower).find()) return MARCAS.get(i);
        }

        return sitio != null ? sitio : "";
    }

    // ──────────────────────────────────────────────────────────────────


    // Talles
    // ──────────────────────────────────────────────────────────────────

    List<String> normalizarTalles(List<String> talles) {
        if (talles == null) return List.of();
        return talles.stream()
                .map(this::normalizarTalle)
                .filter(t -> !t.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizarTalle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.trim().toLowerCase();
        String mapped = TALLE_MAP.get(lower);
        if (mapped != null) return mapped;
        // Devolver el talle original en mayúsculas si parece válido
        String clean = raw.trim().toUpperCase().replaceAll("[^A-Z0-9./]", "");
        return clean.length() <= 6 ? clean : "";
    }

}
