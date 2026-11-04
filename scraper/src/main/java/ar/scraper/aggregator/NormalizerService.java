package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final SportResolver sportResolver;
    private final SiteRegistry siteRegistry;
    private final SitioCategoriaPort sitioCategorias;

    public List<Product> normalizar(List<Product> productos) {
        Map<String, Map<Integer, String>> categoriasPorSitio = categoriasDelArbolPorSitio(productos);
        return productos.stream()
                .map(p -> normalizarProducto(p, categoriasPorSitio.get(p.sitio())))
                .collect(Collectors.toList());
    }

    private Map<String, Map<Integer, String>> categoriasDelArbolPorSitio(List<Product> productos) {
        Set<String> sitiosConArbol = productos.stream()
                .filter(p -> p.sitioCategoriaOrigenId() != null)
                .map(Product::sitio)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Map<Integer, String>> categoriasPorSitio = new HashMap<>();
        for (String sitio : sitiosConArbol) {
            categoriasPorSitio.put(sitio, sitioCategorias.categoriaPorOrigen(sitio));
        }
        return categoriasPorSitio;
    }

    private static String categoriaDelArbol(Product p, Map<Integer, String> categorias) {
        if (categorias == null || p.sitioCategoriaOrigenId() == null) return null;
        return categorias.get(p.sitioCategoriaOrigenId());
    }

    /** In an own-label store the brand is the store, even over a curated match in the name. */
    private String marcaDe(Product p, String nombre, String sitioKey) {
        if (siteRegistry.esMonomarca(sitioKey)) return siteRegistry.porKey(sitioKey).nombre();
        return StringUtils.isBlank(p.marca()) ? brandExtractor.extraer(nombre, p.sitio()) : p.marca();
    }

    private Product normalizarProducto(Product p, Map<Integer, String> categoriasDelArbol) {
        String nombre = StringUtils.defaultString(p.nombre());
        String cat    = categoryClassifier.normalizarCategoria(p.categoria(), nombre,
                categoriaDelArbol(p, categoriasDelArbol));
        String sitioKey       = sitioKey(p.sitio());
        String rubro          = rubroResolver.resolver(sitioKey, cat, p.rubro());
        String genero = genderResolver.resolver(p.genero(), nombre, cat, rubro);
        List<String> talles = sizeNormalizer.normalizar(p.talles());
        String marca          = marcaDe(p, nombre, sitioKey);
        boolean gymrat        = gymratTagger.esGymrat(nombre, sitioKey, cat, rubro, marca);
        String deporte        = sportResolver.resolver(nombre, cat, rubro);
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
                .deporte(deporte)
                .build();
    }
}
