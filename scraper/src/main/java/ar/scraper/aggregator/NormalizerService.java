package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.BrandExtractor;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.RubroResolver;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static ar.scraper.aggregator.normalize.SiteClassification.SITIOS_PREMIUM;
import static ar.scraper.aggregator.normalize.SiteClassification.sitioKey;

/**
 * Normalización profunda post-scraping — orquestador puro.
 *
 * <p>Secuencia (composición, ver design ADR-2): mapea cada {@link Product}
 * crudo a través de los 8 collaborators de {@code ar.scraper.aggregator.normalize}
 * (categoría, género, talles, marca, rubro, gymrat, marca premium, cantidad
 * de unidades, subcategoría) y reconstruye el record {@link Product} — la
 * ÚNICA reconstrucción del record vive acá, en {@link #normalizarProducto}.
 * Todos los collaborators son {@code @Component} beans constructor-inyectados
 * (Work Unit 8 de la modularización SOLID del aggregator); los data/predicate
 * holders ({@code GarmentTaxonomy}, {@code CategoryGroups},
 * {@code SiteClassification}, {@code NonTextileGuard}, Work Unit 3) se siguen
 * consumiendo vía static import/referencia estática dentro de cada
 * collaborator, sin cambios — este orquestador solo usa
 * {@code SiteClassification.sitioKey}/{@code SITIOS_PREMIUM} directamente.</p>
 */
@Component
public class NormalizerService {

    private final PackQuantityDetector packQuantityDetector;
    private final CategoryClassifier categoryClassifier;
    private final BrandExtractor brandExtractor;
    private final GenderResolver genderResolver;
    private final SizeNormalizer sizeNormalizer;
    private final SubcategoryResolver subcategoryResolver;
    private final RubroResolver rubroResolver;
    private final GymratTagger gymratTagger;

    public NormalizerService(PackQuantityDetector packQuantityDetector,
                              CategoryClassifier categoryClassifier,
                              BrandExtractor brandExtractor,
                              GenderResolver genderResolver,
                              SizeNormalizer sizeNormalizer,
                              SubcategoryResolver subcategoryResolver,
                              RubroResolver rubroResolver,
                              GymratTagger gymratTagger) {
        this.packQuantityDetector = packQuantityDetector;
        this.categoryClassifier = categoryClassifier;
        this.brandExtractor = brandExtractor;
        this.genderResolver = genderResolver;
        this.sizeNormalizer = sizeNormalizer;
        this.subcategoryResolver = subcategoryResolver;
        this.rubroResolver = rubroResolver;
        this.gymratTagger = gymratTagger;
    }

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

        String sitioKey       = sitioKey(p.sitio());
        String rubro          = rubroResolver.resolver(sitioKey, cat, p.rubro());
        boolean gymrat        = gymratTagger.esGymrat(nombre, sitioKey, cat, rubro, marca);
        boolean marcaPremium  = SITIOS_PREMIUM.contains(sitioKey);
        int cantidadUnidades  = packQuantityDetector.detectar(nombre, cat);
        String subCategoria   = subcategoryResolver.resolver(nombre, cat);

        return new Product(p.sitio(), nombre, p.precio(), p.precioOriginal(),
                p.url(), p.imagenUrl(), cat, genero, talles,
                p.ml(), marca, rubro, gymrat, marcaPremium, p.senal(),
                p.finan(), cantidadUnidades, subCategoria, p.visual());
    }
}
