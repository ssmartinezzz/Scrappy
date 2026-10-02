package ar.scraper.db;

import ar.scraper.classification.SiteRegistry;
import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.catalog.CatalogResumen;
import ar.scraper.catalog.Facets;
import ar.scraper.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Until now the endpoint streamed the whole in-memory catalog through 18 Java predicates on every
 * request, paginating what was left.
 */
@Repository
class CatalogQueryRepository implements CatalogQueryPort {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogQueryRepository.class);

    private final JdbcTemplate jdbc;
    private final SiteRegistry siteRegistry;

    CatalogQueryRepository(DataSource dataSource, SiteRegistry siteRegistry) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.siteRegistry = siteRegistry;
    }

    @Override
    public CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size) {
        return buscar(filtro, orden, page, size, Optional.empty());
    }

    @Override
    public CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size, Optional<Instant> desde) {
        Where where = construirWhere(filtro, cotaDe(desde));
        try {
            int total = contar(where);
            int paginaClamped = Math.max(page, 1);
            int offset = (int) Math.min((long) (paginaClamped - 1) * size, Math.max(total, 0));

            List<Product> productos = leerPagina(where, orden, size, offset);
            return new CatalogPage(productos, total);
        } catch (Exception e) {
            LOG.error("[DB] Error consultando el catálogo: {}", e.getMessage(), e);
            return new CatalogPage(List.of(), 0);
        }
    }

    /**
     * Cada consulta aplica en SQL la MISMA normalización de clave que hacía {@code FacetCalculator}
     * (trim, lower para género, capitalizar la primera letra en categoría) para que las claves
     * salgan idénticas.
     */
    @Override
    public Facets facetas() {
        return facetas(Optional.empty());
    }

    @Override
    public Facets facetas(Optional<Instant> desde) {
        Cota cota = cotaDe(desde);
        try {
            Map<String, Long> talles = ar.scraper.catalog.TalleOrder.sortTalles(
                    contarHija("producto_talle", "talle", cota));
            Map<String, Long> badges = contarHija("producto_badge", "badge", cota);

            List<Map<String, Long>> filas = contarProductosPorFaceta(cota);

            Map<String, Long> generos = filas.get(0);
            Map<String, Long> categorias = filas.get(1);
            Map<String, Long> marcas = limitar(filas.get(2), 30);
            Map<String, Long> subCategorias = ordenarPorClave(filas.get(3));
            Map<String, Long> fits = filas.get(4);
            Map<String, Long> estampados = filas.get(5);
            Map<String, Long> escotes = filas.get(6);
            Map<String, Long> colores = filas.get(7);

            return new Facets(
                    talles, generos, categorias, marcas, badges, subCategorias,
                    fits, estampados, escotes, colores);
        } catch (Exception e) {
            LOG.error("[DB] Error calculando facetas: {}", e.getMessage(), e);
            return new Facets(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * Las ocho expresiones que antes eran ocho {@code contar(...)} — ocho barridos de
     * {@code productos} — en UNA: un {@code GROUPING SETS} de un solo scan. El orden de
     * {@link #FACET_EXPRS} es el índice:
     */
    private List<Map<String, Long>> contarProductosPorFaceta(Cota cota) {
        String[] exprs = FACET_EXPRS;
        StringBuilder select = new StringBuilder("SELECT ");
        StringBuilder groupingSets = new StringBuilder();
        for (int i = 0; i < exprs.length; i++) {
            if (i > 0) {
                select.append(", ");
                groupingSets.append(", ");
            }
            select.append("GROUPING(k").append(i).append(") AS g").append(i).append(", k").append(i);
            groupingSets.append("(k").append(i).append(')');
        }
        String sql = select + ", COUNT(*) AS cnt FROM (SELECT "
                + joinAliased(exprs) + " FROM productos WHERE activo" + cota.sqlAnd("") + ") x"
                + " GROUP BY GROUPING SETS (" + groupingSets + ")";

        List<Map<String, Long>> crudo = new ArrayList<>(exprs.length);
        for (int i = 0; i < exprs.length; i++) crudo.add(new java.util.LinkedHashMap<>());
        // Acumula en listas primero: el orden final (conteo DESC, clave ASC) se decide en Java por
        // faceta, no en el ORDER BY — mezclar ocho criterios de orden distintos en una sola
        // cláusula no vale la pena.
        List<List<Map.Entry<String, Long>>> porFaceta = new ArrayList<>(exprs.length);
        for (int i = 0; i < exprs.length; i++) porFaceta.add(new ArrayList<>());

        int cntCol = exprs.length * 2 + 1;
        jdbc.query(sql, ps -> cota.bind(ps, 1), rs -> {
            for (int i = 0; i < exprs.length; i++) {
                int gCol = i * 2 + 1;
                int kCol = i * 2 + 2;
                if (rs.getInt(gCol) != 0) continue; // no es la faceta activa de esta fila
                String clave = rs.getString(kCol);
                if (StringUtils.isBlank(clave)) break;
                porFaceta.get(i).add(Map.entry(clave, rs.getLong(cntCol)));
                break;
            }
        });
        for (int i = 0; i < exprs.length; i++) {
            List<Map.Entry<String, Long>> entradas = porFaceta.get(i);
            entradas.sort(Comparator
                    .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey));
            Map<String, Long> destino = crudo.get(i);
            for (Map.Entry<String, Long> e : entradas) destino.put(e.getKey(), e.getValue());
        }
        return crudo;
    }

    private static String joinAliased(String[] exprs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exprs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(exprs[i]).append(" AS k").append(i);
        }
        return sb.toString();
    }

    /** Orden fijo: género, categoría, marca, sub_categoría, fit, estampado, escote, color. */
    private static final String[] FACET_EXPRS = {
            "lower(btrim(genero))",
            "upper(left(btrim(categoria),1)) || lower(substr(btrim(categoria),2))",
            "btrim(marca)",
            "btrim(sub_categoria)",
            "btrim(fit)",
            "btrim(estampado)",
            "btrim(escote)",
            "btrim(color_dominante)",
    };

    @Override
    public CatalogResumen resumen() {
        return resumen(Optional.empty());
    }

    @Override
    public CatalogResumen resumen(Optional<Instant> desde) {
        Cota cota = cotaDe(desde);
        double min = 0, max = 0;
        long conteoGymrat = 0, conteoPacks = 0;
        int total = 0;
        Map<String, Long> porSitio = new java.util.LinkedHashMap<>();
        Map<String, Long> rubros = new java.util.LinkedHashMap<>();
        try {
            Agregado agregado = jdbc.query(
                    "SELECT coalesce(min(precio),0), coalesce(max(precio),0), COUNT(*), "
                            + "count(*) FILTER (WHERE gymrat), count(*) FILTER (WHERE cantidad_unidades > 1) "
                            + "FROM productos WHERE activo" + cota.sqlAnd(""),
                    ps -> cota.bind(ps, 1),
                    rs -> rs.next()
                            ? new Agregado(rs.getDouble(1), rs.getDouble(2), rs.getInt(3), rs.getLong(4), rs.getLong(5))
                            : null);
            if (agregado != null) {
                min = agregado.min();
                max = agregado.max();
                total = agregado.total();
                conteoGymrat = agregado.gymrat();
                conteoPacks = agregado.packs();
            }
            porSitio = contar("sitio", cota);
            rubros = contar("coalesce(nullif(btrim(rubro),''),'indumentaria')", cota);
        } catch (Exception e) {
            LOG.error("[DB] Error calculando el resumen del catálogo: {}", e.getMessage(), e);
        }
        return new CatalogResumen(min, max, porSitio, rubros, conteoGymrat, conteoPacks, total);
    }

    private record Agregado(double min, double max, int total, long gymrat, long packs) {
    }

    private Map<String, Long> contar(String expresion, Cota cota) {
        Map<String, Long> conteo = new java.util.LinkedHashMap<>();
        String sql = "SELECT " + expresion + " AS clave, COUNT(*) FROM productos "
                + "WHERE activo AND coalesce(btrim(" + expresion + "), '') <> ''"
                + cota.sqlAnd("")
                + " GROUP BY 1 ORDER BY 2 DESC, 1 ASC";
        jdbc.query(sql, ps -> cota.bind(ps, 1), rs -> {
            conteo.put(rs.getString(1), rs.getLong(2));
        });
        return conteo;
    }

    /**
     * Igual pero sobre una tabla hija: un producto cuenta UNA VEZ POR VALOR que tiene, no una sola
     * vez — es la semántica multi-badge que la spec pide.
     */
    private Map<String, Long> contarHija(String tabla, String columna, Cota cota) {
        Map<String, Long> conteo = new java.util.LinkedHashMap<>();
        // Leaving this one unbounded is invisible from `buscar` — the page shrinks correctly while
        // the talles and badges filters keep offering values only the held-back products carry.
        String sql = "SELECT btrim(h." + columna + ") AS clave, COUNT(*) FROM " + tabla + " h "
                + "JOIN productos p ON p.url = h.url "
                + "WHERE p.activo AND btrim(h." + columna + ") <> ''"
                + cota.sqlAnd("p.")
                + " GROUP BY 1 ORDER BY 2 DESC, 1 ASC";
        jdbc.query(sql, ps -> cota.bind(ps, 1), rs -> {
            conteo.put(rs.getString(1), rs.getLong(2));
        });
        return conteo;
    }

    private static Map<String, Long> limitar(Map<String, Long> conteo, int max) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        conteo.entrySet().stream().limit(max).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static Map<String, Long> ordenarPorClave(Map<String, Long> conteo) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        conteo.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** SQL fragment plus its bound parameters, in order. */
    private record Where(String sql, List<Object> params) {
    }

    /**
     * The reader bound: while a run is in flight, hold back the rows it has already re-touched so a
     * reader sees the catalogue as it stood before the run started, instead of a half-rescraped
     * mix.
     */
    private record Cota(Optional<Instant> desde) {

        static final Cota SIN_COTA = new Cota(Optional.empty());

        String sqlAnd(String alias) {
            return desde.isEmpty() ? "" : " AND " + alias + "touched_at < ?";
        }

        int bind(PreparedStatement ps, int idx) throws SQLException {
            if (desde.isEmpty()) return idx;
            ps.setObject(idx, desde.get().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
            return idx + 1;
        }
    }

    private static Cota cotaDe(Optional<Instant> desde) {
        return desde == null || desde.isEmpty() ? Cota.SIN_COTA : new Cota(desde);
    }

    private Where construirWhere(CatalogFilter f) {
        return construirWhere(f, Cota.SIN_COTA);
    }

    private Where construirWhere(CatalogFilter f, Cota cota) {
        List<String> cond = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        cond.add("p.activo");
        if (cota.desde().isPresent()) {
            cond.add("p.touched_at < ?");
            params.add(cota.desde().get().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
        }

        if (noVacio(f.sitio())) {
            cond.add("lower(coalesce(p.sitio,'')) = lower(?)");
            params.add(f.sitio());
        }
        if (noVacia(f.talles())) {
            cond.add("EXISTS (SELECT 1 FROM producto_talle t WHERE t.url = p.url "
                    + "AND lower(t.talle) = ANY(?))");
            params.add(new TextArray(enMinusculas(f.talles())));
        }
        if (noVacia(f.marcas())) {
            cond.add("lower(coalesce(p.marca,'')) = ANY(?)");
            params.add(new TextArray(enMinusculas(f.marcas())));
        }
        if (noVacio(f.badge())) {
            cond.add("EXISTS (SELECT 1 FROM producto_badge b WHERE b.url = p.url "
                    + "AND lower(b.badge) = lower(?))");
            params.add(f.badge());
        }
        if (noVacio(f.segment())) {
            cond.add("lower(coalesce(nullif(p.ml_segment,''),'standard')) = lower(?)");
            params.add(f.segment());
        }
        if (noVacio(f.rubro())) {
            cond.add("lower(coalesce(nullif(p.rubro,''),'indumentaria')) = lower(?)");
            params.add(f.rubro());
        }
        if (Boolean.TRUE.equals(f.gymrat())) {
            cond.add("p.gymrat");
        }
        if (Boolean.TRUE.equals(f.pack())) {
            cond.add("p.cantidad_unidades > 1");
        }
        if (f.precioMin() != null) {
            cond.add("(p.precio / GREATEST(p.cantidad_unidades, 1)) >= ?");
            params.add(f.precioMin());
        }
        if (f.precioMax() != null) {
            cond.add("(p.precio / GREATEST(p.cantidad_unidades, 1)) <= ?");
            params.add(f.precioMax());
        }
        if (noVacio(f.genero())) {
            cond.add("lower(coalesce(p.genero,'')) = lower(?)");
            params.add(f.genero());
        }
        if (noVacia(f.categorias())) {
            // El separador es un espacio, no un prefijo pelado, así que "Buzo" nunca matchea
            // "Buzos".
            cond.add("EXISTS (SELECT 1 FROM unnest(?) AS sel(v) WHERE "
                    + "lower(coalesce(p.categoria,'')) = sel.v "
                    + "OR lower(coalesce(p.categoria,'')) LIKE sel.v || ' %' "
                    + "OR sel.v LIKE lower(coalesce(p.categoria,'')) || ' %')");
            params.add(new TextArray(escaparLike(enMinusculas(f.categorias()))));
        }
        if (noVacia(f.subCategorias())) {
            cond.add("lower(coalesce(p.sub_categoria,'')) = ANY(?)");
            params.add(new TextArray(enMinusculas(f.subCategorias())));
        }
        agregarVisual(cond, params, "fit", f.fit());
        agregarVisual(cond, params, "estampado", f.estampado());
        agregarVisual(cond, params, "escote", f.escote());
        agregarVisual(cond, params, "color_dominante", f.colorDominante());

        if (noVacio(f.q())) {
            cond.add("p.nombre ILIKE '%' || ? || '%'");
            params.add(escaparLike(f.q()));
        }
        return new Where(String.join("\n  AND ", cond), params);
    }

    private void agregarVisual(List<String> cond, List<Object> params, String columna, String valor) {
        if (!noVacio(valor)) return;
        cond.add("lower(coalesce(p." + columna + ",'')) = lower(?)");
        params.add(valor);
    }

    /**
     * {@code url} cierra todos los ORDER BY: SQL no tiene sort estable y sin desempate una misma
     * consulta puede devolver un producto en dos páginas distintas (o en ninguna).
     */
    private String orderBy(String orden) {
        return switch (orden != null ? orden : "precio_asc") {
            case "precio_desc" -> "ORDER BY p.precio DESC, p.url ASC";
            case "nombre_asc", "nombre" -> "ORDER BY lower(coalesce(p.nombre,'')) ASC, p.url ASC";
            // Arreglado acá y no replicado: acarrearlo a SQL era convertir un bug de UI en un bug
            // de esquema.
            case "composite", "ml_score" -> "ORDER BY p.ml_score DESC, p.url ASC";
            // El comparador en memoria descartaba los productos sin descuento DENTRO del sort, así
            // que cambiar el orden cambiaba el total y la paginación — total sigue sin cambiar acá.
            case "desc_pct" -> "ORDER BY " + PCT_DESCUENTO + " DESC NULLS LAST, p.url ASC";
            default -> "ORDER BY p.precio ASC, p.url ASC";
        };
    }

    private static final String PCT_DESCUENTO = "((p.precio_orig - p.precio) / p.precio_orig)";

    private int contar(Where where) {
        String sql = "SELECT COUNT(*) FROM productos p WHERE " + where.sql();
        return jdbc.query(sql, ps -> bind(ps, where.params()), rs -> rs.next() ? rs.getInt(1) : 0);
    }

    /**
     * Cuatro sentencias por página, TODAS constantes en el tamaño del catálogo: las urls de la
     * página (con el filtro y el orden), los productos de esas urls, y sus dos tablas hijas.
     */
    private List<Product> leerPagina(Where where, String orden, int size, int offset) {
        List<String> urls = new ArrayList<>();
        String sqlUrls = "SELECT p.url FROM productos p WHERE " + where.sql()
                + "\n" + orderBy(orden) + "\nLIMIT ? OFFSET ?";
        jdbc.query(sqlUrls, ps -> {
            int i = bind(ps, where.params());
            ps.setInt(i++, size);
            ps.setInt(i, offset);
        }, rs -> {
            urls.add(rs.getString(1));
        });
        if (urls.isEmpty()) return List.of();

        Map<String, List<String>> talles = multivalor("producto_talle", "talle", urls);
        Map<String, List<String>> badges = multivalor("producto_badge", "badge", urls);

        Map<String, Product> porUrl = new HashMap<>();
        jdbc.query(ProductRowMapper.COLUMNAS + " p WHERE p.url = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", urls.toArray())),
                rs -> {
                    String url = rs.getString("url");
                    porUrl.put(url, ProductRowMapper.map(rs,
                            talles.getOrDefault(url, List.of()),
                            badges.getOrDefault(url, List.of()), siteRegistry));
                });

        List<Product> pagina = new ArrayList<>(urls.size());
        for (String url : urls) {
            Product p = porUrl.get(url);
            if (p != null) pagina.add(p);
        }
        return pagina;
    }

    private Map<String, List<String>> multivalor(String tabla, String columna, List<String> urls) {
        Map<String, List<String>> porUrl = new HashMap<>();
        jdbc.query("SELECT url," + columna + " FROM " + tabla + " WHERE url = ANY(?) ORDER BY url, posicion",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", urls.toArray())),
                rs -> {
                    porUrl.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return porUrl;
    }

    private record TextArray(List<String> valores) {
    }

    private int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            if (p instanceof TextArray arr) {
                ps.setArray(i++, ps.getConnection().createArrayOf("text", arr.valores().toArray()));
            } else {
                ps.setObject(i++, p);
            }
        }
        return i;
    }

    private static boolean noVacio(String s) {
        return StringUtils.isNotBlank(s);
    }

    private static boolean noVacia(List<String> l) {
        return l != null && !l.isEmpty();
    }

    private static List<String> enMinusculas(List<String> valores) {
        List<String> out = new ArrayList<>(valores.size());
        for (String v : valores) out.add(v != null ? v.toLowerCase(Locale.ROOT) : "");
        return out;
    }

    private static String escaparLike(String valor) {
        return valor.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> escaparLike(List<String> valores) {
        List<String> out = new ArrayList<>(valores.size());
        for (String v : valores) out.add(escaparLike(v));
        return out;
    }
}
