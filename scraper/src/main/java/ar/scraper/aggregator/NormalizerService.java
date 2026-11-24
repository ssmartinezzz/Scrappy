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
import java.util.Optional;
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
    // Home-textile stores: their names read as clothing or kits ("Set x4 Individuales" is a Conjunto, "Funda de Acolchado"
    // a Puffer, "Tejido" a Sweater), so only a home category from the name counts there; anything else yields to the leaf.
    private static final Set<String> SITIOS_SOLO_HOGAR = Set.of("arredo", "sommiercenter");
    // A hardware and paint store: the same guard, but building and sports categories count too (a padel racket, a drill). Its
    // listing has no category, so a "20 Kg" paint reads as food and "Rodillo Lana" as a sweater unless the guard is there.
    private static final Set<String> SITIOS_SOLO_HOGAR_Y_OBRA = Set.of("somosrex");
    // What such a store sells besides the building and home lines: chairs, TVs bundled with a rack, work clothes.
    private static final Set<String> CATEGORIAS_DE_OBRA_Y_HOGAR_EXTRA = Set.of("Silla", "TV y Audio", "Pantalón", "Camisa");
    // A curated "Celular" leaf (phones and their accessories) outranks the name unless the name says another device:
    // a Galaxy Tab on a phone series leaf is a Tablet, a watch a Reloj.
    private static final Set<String> OTROS_EQUIPOS_QUE_NO_CEDEN = Set.of(
            "Tablet", "Notebook", "Reloj", "Auricular", "Monitor", "TV y Audio", "PC", "Mini PC", "Consola", "Cámara",
            "Accesorio Apple", "Accesorio Gaming");
    // Stores whose tree is curated end to end: the leaf outranks the name (a fridge drawer is not an office organizer,
    // a Mini LED TV is not a bag), except where the name says another device.
    private static final Set<String> SITIOS_DONDE_LA_HOJA_GANA = Set.of("samsung", "prestigio", "naldo", "cetrogar", "oncity", "dexter", "moov");
    // Sites whose whole catalogue is one gender, whatever the name says. A toy store sells to kids: its
    // backpacks, shoes and dolls' dresses are not adult picks. Equus is a men's-only shop. Ayrun is women's
    // gymwear whose names carry no gender word ("Musculosa Seamless Animal"). Somos Rex and Sommier Center are
    // hardware and bedding on the Magento page that tags every card "mujer" (Laspepas): blank, they have no gender.
    private static final Map<String, String> GENERO_FIJO_POR_SITIO =
            Map.of("elmundodeljuguete", "infantil", "equus", "hombre", "laspepas", "mujer", "ayrun", "mujer",
                    "somosrex", "", "sommiercenter", "");
    // Fixed-gender sites whose scraper tags a kids line apart: that tag outranks the fixed gender.
    private static final Set<String> SITIOS_CON_LINEA_INFANTIL = Set.of("laspepas");

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

    private static String soloHogar(String sitioKey, String categoria, String categoriaDelArbol) {
        boolean yObra = SITIOS_SOLO_HOGAR_Y_OBRA.contains(sitioKey);
        if (!yObra && !SITIOS_SOLO_HOGAR.contains(sitioKey)) return categoria;
        if (CategoryGroups.esCategoriaHogar(categoria)
                || (yObra && (CategoryGroups.esCategoriaConstruccion(categoria) || CategoryGroups.esCategoriaDeporte(categoria)
                    || CATEGORIAS_DE_OBRA_Y_HOGAR_EXTRA.contains(categoria))))
            return categoria;
        return CategoryGroups.esCategoriaHogar(categoriaDelArbol) ? categoriaDelArbol : "Otros";
    }

    private static String celularDelArbol(String sitioKey, String categoriaDelArbol, String categoria) {
        boolean hojaGana = "Celular".equals(categoriaDelArbol) || SITIOS_DONDE_LA_HOJA_GANA.contains(sitioKey);
        return hojaGana && StringUtils.isNotBlank(categoriaDelArbol) && !OTROS_EQUIPOS_QUE_NO_CEDEN.contains(categoria)
                ? categoriaDelArbol : categoria;
    }

    private static boolean esOtrosEnSuSitio(String sitioKey, String nombre) {
        Set<String> palabras = PALABRAS_OTROS_POR_SITIO.get(sitioKey);
        if (palabras == null) return false;
        String limpio = NO_ALNUM.matcher(AccentStripper.strip(nombre.toLowerCase())).replaceAll(" ");
        return Arrays.stream(limpio.trim().split(" ")).anyMatch(palabras::contains);
    }

    private static Optional<String> generoFijo(String sitioKey, String generoScrapeado) {
        if ("infantil".equals(generoScrapeado) && SITIOS_CON_LINEA_INFANTIL.contains(sitioKey))
            return Optional.of("infantil");
        return Optional.ofNullable(GENERO_FIJO_POR_SITIO.get(sitioKey));
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
        String cat            = celularDelArbol(sitioKey, delArbol, hojaJugueteGana(sitioKey, delArbol)
                ? CategoryClassifier.separarLego(delArbol, nombre)
                : esOtrosEnSuSitio(sitioKey, nombre)
                        ? "Otros"
                        : soloHogar(sitioKey, categoryClassifier.normalizarCategoria(p.categoria(), nombre, delArbol), delArbol));
        String subCategoria   = subcategoryResolver.resolver(nombre, cat);
        String rubro          = rubroResolver.resolver(sitioKey, cat, p.rubro(), subCategoria);
        String genero         = generoFijo(sitioKey, p.genero())
                .orElseGet(() -> genderResolver.resolver(p.genero(), nombre, cat, rubro));
        List<String> talles = sizeNormalizer.normalizar(p.talles());
        String marca          = marcaDe(p, nombre, sitioKey);
        boolean gymrat        = gymratTagger.esGymrat(nombre, sitioKey, cat, rubro, marca);
        String deporte        = sportResolver.resolver(nombre, cat, rubro);
        boolean marcaPremium  = siteRegistry.esPremium(sitioKey);
        int cantidadUnidades  = packQuantityDetector.detectar(nombre, cat);

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
