package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

import static ar.scraper.classification.SiteClassification.sitioKey;

/** Normalización profunda post-scraping — orquestador puro. */
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
    private final SiteRegistry siteRegistry;

    public NormalizerService(PackQuantityDetector packQuantityDetector,
                              CategoryClassifier categoryClassifier,
                              BrandExtractor brandExtractor,
                              GenderResolver genderResolver,
                              SizeNormalizer sizeNormalizer,
                              SubcategoryResolver subcategoryResolver,
                              RubroResolver rubroResolver,
                              GymratTagger gymratTagger,
                              SiteRegistry siteRegistry) {
        this.packQuantityDetector = packQuantityDetector;
        this.categoryClassifier = categoryClassifier;
        this.brandExtractor = brandExtractor;
        this.genderResolver = genderResolver;
        this.sizeNormalizer = sizeNormalizer;
        this.subcategoryResolver = subcategoryResolver;
        this.rubroResolver = rubroResolver;
        this.gymratTagger = gymratTagger;
        this.siteRegistry = siteRegistry;
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
        String marca  = StringUtils.isBlank(p.marca())
                        ? brandExtractor.extraer(nombre, p.sitio())
                        : p.marca();

        String sitioKey       = sitioKey(p.sitio());
        String rubro          = rubroResolver.resolver(sitioKey, cat, p.rubro());
        boolean gymrat        = gymratTagger.esGymrat(nombre, sitioKey, cat, rubro, marca);
        boolean marcaPremium  = siteRegistry.esPremium(sitioKey);
        int cantidadUnidades  = packQuantityDetector.detectar(nombre, cat);
        String subCategoria   = subcategoryResolver.resolver(nombre, cat);

        return Product.builder()
                .sitio(p.sitio())
                .nombre(nombre)
                .precio(p.precio())
                .precioOriginal(p.precioOriginal())
                .url(p.url())
                .imagenUrl(p.imagenUrl())
                .categoria(cat)
                .genero(genero)
                .talles(talles)
                .ml(p.ml())
                .marca(marca)
                .rubro(rubro)
                .gymrat(gymrat)
                .marcaPremium(marcaPremium)
                .senal(p.senal())
                .finan(p.finan())
                .cantidadUnidades(cantidadUnidades)
                .subCategoria(subCategoria)
                .visual(p.visual())
                .build();
    }
}
