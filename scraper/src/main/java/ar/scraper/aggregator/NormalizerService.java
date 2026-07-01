package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.BrandExtractor;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.*;
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
 * detection is delegated to {@link PackQuantityDetector} (Work Unit 4),
 * category classification to {@link CategoryClassifier} (Work Unit 5), and
 * brand/gender/size resolution to {@link BrandExtractor}, {@link GenderResolver},
 * and {@link SizeNormalizer} (Work Unit 6). This class still owns subcategory
 * resolution, gymrat tagging, rubro selection, and orchestration; those
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
    private final BrandExtractor brandExtractor = new BrandExtractor();
    private final GenderResolver genderResolver = new GenderResolver();
    private final SizeNormalizer sizeNormalizer = new SizeNormalizer();

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
        String genero = genderResolver.resolver(p.genero(), nombre, cat);
        List<String> talles = sizeNormalizer.normalizar(p.talles());
        String marca  = (p.marca() == null || p.marca().isBlank())
                        ? brandExtractor.extraer(nombre, p.sitio())
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

    /**
     * Accent-normalization helper used by {@link #resolverSubCategoria}
     * (SubcategoryResolver extraction lands in Work Unit 7). Género/marca/talle
     * accent handling moved with their respective collaborators in Work Unit 6
     * — {@code GenderResolver} delegates to {@code AccentStripper} (ADR-4);
     * {@code BrandExtractor} and the size normalizer never needed accent
     * stripping (see {@code BrandExtractor}/{@code SizeNormalizer} javadoc).
     */
    private String normalizarAcentos(String s) {
        return s.toLowerCase()
                .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
    }
}
