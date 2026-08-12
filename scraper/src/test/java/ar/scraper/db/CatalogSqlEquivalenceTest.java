package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `sql-catalog-filtering` — the safety net for moving `/api/data`'s filters
 * from Java to SQL.
 *
 * <p>Each test runs the SAME filter twice over the SAME dataset: once through
 * the reference implementation (the in-memory predicates `/api/data` has always
 * used, copied here verbatim as an oracle) and once through
 * {@link CatalogQueryRepository}. The two must agree on the exact set of URLs.
 * Anything else — a filter that quietly widens, a default that stops applying,
 * a case-sensitivity that flips — shows up as a diff instead of as a support
 * ticket about a product that disappeared from the catalog.</p>
 *
 * <p>The dataset is deliberately awkward: mixed casing, accents, packs, missing
 * ML segments, products with no sizes at all, categories that are prefixes of
 * each other, and a name containing a SQL wildcard.</p>
 */
@Epic("Persistence")
@Feature("Catalog query")
@Story("SQL filtering matches the in-memory filtering it replaces")
@DisplayName("/api/data — SQL filtering is equivalent to the in-memory filtering")
class CatalogSqlEquivalenceTest extends PostgresTestBase {

    private DatabaseService db;
    private CatalogQueryRepository repo;
    private List<Product> dataset;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        repo = new CatalogQueryRepository(dataSource());
        dataset = dataset();
        db.upsertProductos(dataset);
    }

    // ─── los casos ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sin filtros: el mismo catálogo activo")
    void sinFiltros() {
        verificarEquivalencia(CatalogFilter.todo());
    }

    @Test
    @DisplayName("talle: OR entre valores, case-insensitive, vía producto_talle")
    void filtroTalle() {
        verificarEquivalencia(CatalogFilter.todo().conTalles(List.of("m")));
        verificarEquivalencia(CatalogFilter.todo().conTalles(List.of("M", "XL")));
        verificarEquivalencia(CatalogFilter.todo().conTalles(List.of("no-existe")));
    }

    @Test
    @DisplayName("badge: pertenencia al set, no igualdad con el principal")
    void filtroBadge() {
        verificarEquivalencia(CatalogFilter.todo().conBadge("trending"));
        verificarEquivalencia(CatalogFilter.todo().conBadge("TRENDING"));
        verificarEquivalencia(CatalogFilter.todo().conBadge("all_time_low"));
    }

    @Test
    @DisplayName("categoria: prefijo en las dos direcciones, nunca prefijo pelado")
    void filtroCategoria() {
        verificarEquivalencia(CatalogFilter.todo().conCategorias(List.of("Zapatilla")));
        verificarEquivalencia(CatalogFilter.todo().conCategorias(List.of("Zapatilla Running")));
        verificarEquivalencia(CatalogFilter.todo().conCategorias(List.of("Buzo")));
    }

    @Test
    @DisplayName("precio: sobre el precio UNITARIO, no el del pack")
    void filtroPrecioUnitario() {
        verificarEquivalencia(CatalogFilter.todo().conRangoPrecio(null, 1000.0));
        verificarEquivalencia(CatalogFilter.todo().conRangoPrecio(1000.0, 5000.0));
        verificarEquivalencia(CatalogFilter.todo().conPack(true));
    }

    @Test
    @DisplayName("q: substring case-insensitive, y un % literal no es comodín")
    void filtroTextoLibre() {
        verificarEquivalencia(CatalogFilter.todo().conQ("remera"));
        verificarEquivalencia(CatalogFilter.todo().conQ("REMERA"));
        verificarEquivalencia(CatalogFilter.todo().conQ("%"));
    }

    @Test
    @DisplayName("genero: el vacío es un valor real, no 'sin filtro'")
    void filtroGenero() {
        verificarEquivalencia(CatalogFilter.todo().conGenero("hombre"));
        verificarEquivalencia(CatalogFilter.todo().conGenero("Hombre"));
    }

    @Test
    @DisplayName("Filtros combinados")
    void filtrosCombinados() {
        verificarEquivalencia(CatalogFilter.todo()
                .conTalles(List.of("M"))
                .conGenero("hombre")
                .conRangoPrecio(0.0, 100000.0));
    }

    @Test
    @DisplayName("La paginación no repite ni se saltea productos")
    void paginacionCubreTodoUnaSolaVez() {
        List<String> vistas = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            CatalogPage p = repo.buscar(CatalogFilter.todo(), "precio_asc", page, 2);
            p.productos().forEach(prod -> vistas.add(prod.url()));
        }
        assertThat(vistas).doesNotHaveDuplicates();
        assertThat(vistas).containsExactlyInAnyOrderElementsOf(
                dataset.stream().map(Product::url).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("El total es el del filtro, no el de la página")
    void totalEsElDelFiltro() {
        CatalogPage p = repo.buscar(CatalogFilter.todo(), "precio_asc", 1, 2);
        assertThat(p.productos()).hasSize(2);
        assertThat(p.total()).isEqualTo(dataset.size());
    }

    @Test
    @DisplayName("Los talles vuelven ordenados, y el badge principal sigue primero")
    void hidratacionDeTablasHijas() {
        CatalogPage p = repo.buscar(
                CatalogFilter.todo().conQ("multi-talle"), "precio_asc", 1, 10);

        assertThat(p.productos()).hasSize(1);
        assertThat(p.productos().get(0).talles()).containsExactly("S", "M", "L", "XL");
        assertThat(p.productos().get(0).ml().badges()).containsExactly("all_time_low", "trending");
        assertThat(p.productos().get(0).ml().badge()).isEqualTo("all_time_low");
    }

    // ─── el oráculo: los predicados que /api/data usó siempre ────────────────

    private void verificarEquivalencia(CatalogFilter f) {
        List<String> esperadas = referencia(f).stream().map(Product::url).sorted().toList();

        CatalogPage pagina = repo.buscar(f, "precio_asc", 1, 500);
        List<String> obtenidas = pagina.productos().stream().map(Product::url).sorted().toList();

        assertThat(obtenidas)
                .as("SQL y el filtrado en memoria tienen que coincidir exactamente")
                .isEqualTo(esperadas);
        assertThat(pagina.total()).as("el total también").isEqualTo(esperadas.size());
    }

    /** Copia literal de la lógica de CatalogoEndpoints.aplicarFiltros. */
    private List<Product> referencia(CatalogFilter f) {
        return dataset.stream().filter(p -> {
            if (noVacio(f.sitio()) && !safe(p.sitio()).equalsIgnoreCase(f.sitio())) return false;
            if (noVacia(f.talles())) {
                if (p.talles() == null || p.talles().isEmpty()) return false;
                if (f.talles().stream().noneMatch(t ->
                        p.talles().stream().anyMatch(pt -> pt.equalsIgnoreCase(t)))) return false;
            }
            if (noVacia(f.marcas())
                    && f.marcas().stream().noneMatch(sel -> safe(p.marca()).equalsIgnoreCase(sel))) return false;
            if (noVacio(f.badge())) {
                List<String> b = (p.ml() != null && p.ml().badges() != null) ? p.ml().badges() : List.of();
                if (b.stream().noneMatch(bg -> bg.equalsIgnoreCase(f.badge()))) return false;
            }
            if (noVacio(f.segment())) {
                String seg = (p.ml() != null && p.ml().segment() != null) ? p.ml().segment() : "standard";
                if (!seg.equalsIgnoreCase(f.segment())) return false;
            }
            if (noVacio(f.rubro())) {
                String rb = p.rubro() != null ? p.rubro() : "indumentaria";
                if (!rb.equalsIgnoreCase(f.rubro())) return false;
            }
            if (Boolean.TRUE.equals(f.gymrat()) && !p.gymrat()) return false;
            if (Boolean.TRUE.equals(f.pack()) && !p.esPack()) return false;
            double unitario = p.cantidadUnidades() > 0 ? p.precio() / p.cantidadUnidades() : p.precio();
            if (f.precioMin() != null && unitario < f.precioMin()) return false;
            if (f.precioMax() != null && unitario > f.precioMax()) return false;
            if (noVacio(f.genero()) && !safe(p.genero()).equalsIgnoreCase(f.genero())) return false;
            if (noVacia(f.categorias())) {
                String prodCat = safe(p.categoria()).toLowerCase();
                boolean match = f.categorias().stream().anyMatch(sel -> {
                    String s = sel.toLowerCase();
                    return prodCat.equals(s) || prodCat.startsWith(s + " ") || s.startsWith(prodCat + " ");
                });
                if (!match) return false;
            }
            if (noVacia(f.subCategorias())
                    && f.subCategorias().stream().noneMatch(sel -> safe(p.subCategoria()).equalsIgnoreCase(sel)))
                return false;
            Product.VisualAttrs v = p.visual() != null ? p.visual() : Product.VisualAttrs.EMPTY;
            if (noVacio(f.fit()) && !f.fit().equalsIgnoreCase(v.fit())) return false;
            if (noVacio(f.estampado()) && !f.estampado().equalsIgnoreCase(v.estampado())) return false;
            if (noVacio(f.escote()) && !f.escote().equalsIgnoreCase(v.escote())) return false;
            if (noVacio(f.colorDominante()) && !f.colorDominante().equalsIgnoreCase(v.colorDominante()))
                return false;
            if (noVacio(f.q()) && !safe(p.nombre()).toLowerCase().contains(f.q().toLowerCase())) return false;
            return true;
        }).sorted(Comparator.comparingDouble(Product::precio)).collect(Collectors.toList());
    }

    private static boolean noVacio(String s) { return s != null && !s.isBlank(); }
    private static boolean noVacia(List<String> l) { return l != null && !l.isEmpty(); }
    private static String safe(String s) { return s != null ? s : ""; }

    // ─── dataset ────────────────────────────────────────────────────────────

    private List<Product> dataset() {
        return List.of(
                producto("https://s.com/1", "Remera básica", 1500, "Freres", "Remeras", "hombre",
                        List.of("S", "M"), List.of("trending"), "Nike", 1, "standard"),
                // genero en minúscula: el CHECK de V6 rechaza "Hombre", y el
                // case-insensitive que importa probar es el del FILTRO, no el del dato.
                producto("https://s.com/2", "REMERA oversize", 2500, "Freres", "Remeras", "hombre",
                        List.of("L"), List.of(), "Adidas", 1, "premium"),
                producto("https://s.com/3", "Zapatilla urbana", 45000, "VCP", "Zapatilla", "mujer",
                        List.of("38", "40"), List.of("all_time_low", "below_market"), "Puma", 1, null),
                producto("https://s.com/4", "Zapatilla de running", 62000, "VCP", "Zapatilla Running", "mujer",
                        List.of("39"), List.of(), "Nike", 1, "luxury"),
                producto("https://s.com/5", "Buzo canguro", 18000, "Midway", "Buzos", "unisex",
                        List.of(), List.of("trending"), "Barnes", 1, "standard"),
                producto("https://s.com/6", "Pack x3 medias multi-talle", 9000, "Bulks", "Medias", "unisex",
                        List.of("S", "M", "L", "XL"), List.of("all_time_low", "trending"), "Bulks", 3, "budget"),
                producto("https://s.com/7", "Short 50% off", 7000, "Batuk", "Shorts", "",
                        List.of("M"), List.of(), "Batuk", 1, "standard")
        );
    }

    private Product producto(String url, String nombre, double precio, String sitio, String categoria,
                             String genero, List<String> talles, List<String> badges, String marca,
                             int unidades, String segment) {
        Product.MlScore ml = new Product.MlScore(
                70, badges, false, "estable", 50, 0.0, segment);
        return new Product(sitio, nombre, precio, null, url, "http://img.example/x.jpg",
                categoria, genero, talles, ml, marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, unidades);
    }
}
