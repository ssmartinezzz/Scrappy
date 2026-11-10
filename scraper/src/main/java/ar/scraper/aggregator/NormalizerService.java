package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroResolver;
import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import ar.scraper.aggregator.text.AccentStripper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

import static ar.scraper.classification.SiteClassification.sitioKey;

/** Normalización profunda post-scraping — orquestador puro. */
@Component
@RequiredArgsConstructor
public class NormalizerService {

    /**
     * Sites whose curated toy leaf outranks the name classifier: their names read as clothing, garden
     * or food ("Set de bloques" is a Conjunto, "con Vestido" a Vestido) while the leaf is exact.
     */
    private static final Set<String> SITIOS_DONDE_LA_HOJA_JUGUETE_GANA = Set.of("elmundodeljuguete");
    // Sites whose whole catalogue is one gender, whatever the name says. A toy store sells to kids: its
    // backpacks, shoes and dolls' dresses are not adult picks. Equus is a men's-only shop.
    private static final Map<String, String> GENERO_FIJO_POR_SITIO =
            Map.of("elmundodeljuguete", "infantil", "equus", "hombre");

    // Words that make a product "Otros" on one store only: they are legitimate categories elsewhere (a hammock
    // is "Reposera y Hamaca" on a garden store, a tote bag a Bolso, "combo" a kit on supplement and PC stores).
    // Whole words, accent-stripped, matched on the name.
    private static final Map<String, Set<String>> PALABRAS_OTROS_POR_SITIO = Map.of(
            "oneplaceworld", Set.of("hamaca", "hamacas", "combo", "combos"),
            "kingofthekongo", Set.of("tote"));
    private static final Pattern NO_ALNUM = Pattern.compile("[^a-z0-9]+");

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

    private static boolean hojaJugueteGana(String sitioKey, String categoriaDelArbol) {
        return SITIOS_DONDE_LA_HOJA_JUGUETE_GANA.contains(sitioKey)
                && CategoryGroups.esCategoriaJuguetes(categoriaDelArbol);
    }

    private static boolean esOtrosEnSuSitio(String sitioKey, String nombre) {
        Set<String> palabras = PALABRAS_OTROS_POR_SITIO.get(sitioKey);
        if (palabras == null) return false;
        String limpio = NO_ALNUM.matcher(AccentStripper.strip(nombre.toLowerCase())).replaceAll(" ");
        return Arrays.stream(limpio.trim().split(" ")).anyMatch(palabras::contains);
    }

    /** In an own-label store the brand is the store, even over a curated match in the name. */
    private String marcaDe(Product p, String nombre, String sitioKey) {
        if (siteRegistry.esMonomarca(sitioKey)) return siteRegistry.porKey(sitioKey).nombre();
        return StringUtils.isBlank(p.marca()) ? brandExtractor.extraer(nombre, p.sitio()) : p.marca();
    }

    private Product normalizarProducto(Product p, Map<Integer, String> categoriasDelArbol) {
        String nombre = StringUtils.defaultString(p.nombre());
        String sitioKey       = sitioKey(p.sitio());
        String delArbol       = categoriaDelArbol(p, categoriasDelArbol);
        String cat            = hojaJugueteGana(sitioKey, delArbol)
                ? delArbol
                : esOtrosEnSuSitio(sitioKey, nombre)
                        ? "Otros"
                        : categoryClassifier.normalizarCategoria(p.categoria(), nombre, delArbol);
        String rubro          = rubroResolver.resolver(sitioKey, cat, p.rubro());
        String genero         = GENERO_FIJO_POR_SITIO.getOrDefault(sitioKey,
                genderResolver.resolver(p.genero(), nombre, cat, rubro));
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
