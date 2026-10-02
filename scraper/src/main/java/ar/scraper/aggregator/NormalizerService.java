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
import lombok.RequiredArgsConstructor;

import static ar.scraper.classification.SiteClassification.sitioKey;

/** Normalización profunda post-scraping — orquestador puro. */
@Component
@RequiredArgsConstructor
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

        return p.toBuilder()
                .nombre(nombre)
                .categoria(cat)
                .genero(genero)
                .talles(talles)
                .marca(marca)
                .rubro(rubro)
                .gymrat(gymrat)
                .marcaPremium(marcaPremium)
                .cantidadUnidades(cantidadUnidades)
                .subCategoria(subCategoria)
                .build();
    }
}
