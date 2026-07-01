package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.BrandExtractor;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
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
 * category classification to {@link CategoryClassifier} (Work Unit 5),
 * brand/gender/size resolution to {@link BrandExtractor}, {@link GenderResolver},
 * and {@link SizeNormalizer} (Work Unit 6), and subcategory resolution to
 * {@link SubcategoryResolver} (Work Unit 7). This class still owns gymrat
 * tagging, rubro selection, and orchestration; those collaborators are
 * extracted in later work units.</p>
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
    private final SubcategoryResolver subcategoryResolver = new SubcategoryResolver();

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
        String subCategoria  = subcategoryResolver.resolver(nombre, cat);

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

}
