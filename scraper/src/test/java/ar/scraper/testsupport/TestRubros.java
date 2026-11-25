package ar.scraper.testsupport;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroCatalogo;
import ar.scraper.classification.RubroCatalogo.CategoriaRubro;
import ar.scraper.classification.RubroCatalogo.Rubro;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.indices.DeflactorPorRubro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The rubro taxonomy as {@link CategoryGroups} defines it, built without a database. It is the oracle of
 * {@code RubroTaxonomiaAgreementTest}: the migrated tables must say the same thing, so a test that uses this
 * factory is not testing a copy that can drift.
 */
public final class TestRubros {

    public static final String USD = "USD_OFICIAL";

    /** In {@code frontend/src/lib/rubros.js} order. */
    private static final List<Rubro> RUBROS = List.of(
            new Rubro("indumentaria", "Indumentaria", 1, "IPC", 0, 3),
            new Rubro("tecnologia", "Tecnología", 2, USD, 2, 0),
            new Rubro("suplementos", "Suplementos", 3, "IPC", 0, 2),
            new Rubro("oficina", "Oficina", 4, "IPC", 1, 0),
            new Rubro("construccion", "Construcción", 5, "IPC", 0, 1),
            new Rubro("hogar", "Hogar", 6, "IPC", 0, 1),
            new Rubro("deporte", "Deporte", 7, "IPC", 0, 0),
            new Rubro("juguetes", "Juguetes", 8, "IPC", 0, 0),
            new Rubro("libros", "Libros", 9, "IPC", 0, 0),
            new Rubro("belleza", "Belleza", 10, "IPC", 0, 1),
            new Rubro("electrodomesticos", "Electrodomésticos", 11, "IPC", 0, 1));

    /** (categoria, sub) pairs whose rubro differs from their category's: a dining, garden or children's chair is a home product. */
    private static final List<String[]> SUBS_DE_SILLA = List.of(
            new String[] {"Silla", "comedor"}, new String[] {"Silla", "exterior"}, new String[] {"Silla", "infantil"});

    /** The subs of {@code TV y Audio} that are home appliances: a store with no forced rubro files them under electrodomesticos. */
    public static final List<String> SUBS_ELECTRO_DE_TV_Y_AUDIO = List.of(
            "Televisor", "Parlante", "Soporte", "Barra de Sonido", "Proyector", "Radio", "Tocadiscos", "Equipo de Música");

    private TestRubros() {}

    public static RubroCatalogo catalogo() {
        List<CategoriaRubro> filas = new ArrayList<>();
        for (String categoria : new TreeSet<>(CategoryGroups.canonicalCategories())) {
            String rubro = rubroDeCategoria(categoria);
            if (rubro != null) filas.add(new CategoriaRubro(categoria, "", rubro, false));
        }
        for (String[] par : SUBS_DE_SILLA) filas.add(new CategoriaRubro(par[0], par[1], "hogar", false));
        for (String sub : SUBS_ELECTRO_DE_TV_Y_AUDIO) filas.add(new CategoriaRubro("TV y Audio", sub, "electrodomesticos", false));
        return new RubroCatalogo(RUBROS, filas, Map.of("TV y Audio", USD));
    }

    public static RubroResolver resolver(SiteRegistry registry) {
        return new RubroResolver(registry, catalogo());
    }

    public static DeflactorPorRubro deflactor() {
        RubroCatalogo catalogo = catalogo();
        return new DeflactorPorRubro(catalogo::indiceDeCategoria, catalogo::indiceDeRubro);
    }

    /** The rubro of a category on a site with no forced or default rubro; {@code null} when the category has none. */
    static String rubroDeCategoria(String cat) {
        if (CategoryGroups.esCategoriaBelleza(cat)) return "belleza";
        if (CategoryGroups.esCategoriaConstruccion(cat)) return "construccion";
        if (CategoryGroups.esCategoriaElectrodomesticos(cat)) return "electrodomesticos";
        if (CategoryGroups.esCategoriaHogar(cat)) return "hogar";
        if (CategoryGroups.esCategoriaSuplemento(cat)) return "suplementos";
        if (CategoryGroups.esIndumentariaOCalzado(cat)) return "indumentaria";
        if (CategoryGroups.categoriasOficina().contains(cat)) return "oficina";
        if (CategoryGroups.esCategoriaDeporte(cat)) return "deporte";
        if (CategoryGroups.esCategoriaLibros(cat)) return "libros";
        if (CategoryGroups.esCategoriaJuguetes(cat)) return "juguetes";
        if (CategoryGroups.esCategoriaTech(cat)) return "tecnologia";
        return null;
    }
}
