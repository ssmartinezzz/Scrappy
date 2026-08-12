package ar.scraper.db;

import ar.scraper.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * `/api/data`'s catalog query, in SQL (`sql-catalog-filtering`).
 *
 * <p>Until now the endpoint streamed the whole in-memory catalog through 18
 * Java predicates on every request, paginating what was left. The two filters
 * that could NOT be expressed in SQL were {@code talle} and {@code badge} —
 * both were lists packed into a single TEXT column. That is precisely what V7's
 * child tables removed, so this is the payoff that migration was for.</p>
 *
 * <p><b>The semantics are reproduced, not improved.</b> Every quirk of the Java
 * filter is mirrored here on purpose — the defaulted {@code segment}/{@code rubro},
 * the two-way prefix match on {@code categoria}, the unit-price range, badge
 * membership rather than equality. {@code CatalogSqlEquivalenceTest} pins them
 * against the original implementation over the same dataset. Anything worth
 * changing gets changed afterwards, visibly, not smuggled in as "while I was
 * there".</p>
 *
 * <p>One deliberate difference: SQL has no stable sort, so every ORDER BY ends
 * with {@code url} as a tiebreaker. Without it, two products at the same price
 * could swap places between page 1 and page 2 of the same query and a product
 * would be shown twice or never.</p>
 */
class CatalogQueryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogQueryRepository.class);

    /** A page of results plus the total the filter matched (not the page size). */
    record Pagina(List<Product> productos, int total) {
    }

    private final DataSource dataSource;

    CatalogQueryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    Pagina buscar(CatalogFilter filtro, String orden, int page, int size) {
        Where where = construirWhere(filtro, orden);
        try (Connection c = dataSource.getConnection()) {
            int total = contar(c, where);
            int paginaClamped = Math.max(page, 1);
            int offset = (int) Math.min((long) (paginaClamped - 1) * size, Math.max(total, 0));

            List<Product> productos = leerPagina(c, where, orden, size, offset);
            return new Pagina(productos, total);
        } catch (Exception e) {
            LOG.error("[DB] Error consultando el catálogo: {}", e.getMessage(), e);
            return new Pagina(List.of(), 0);
        }
    }

    // ─── WHERE ──────────────────────────────────────────────────────────────

    /** SQL fragment plus its bound parameters, in order. */
    private record Where(String sql, List<Object> params) {
    }

    private Where construirWhere(CatalogFilter f, String orden) {
        List<String> cond = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        cond.add("p.activo");

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
            // El default vive en el mapper (ml_segment NULL -> "standard"), así que
            // el filtro tiene que comparar contra el valor YA defaulteado.
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
            // Prefijo en LAS DOS direcciones: "Zapatilla" matchea "Zapatilla Running"
            // y al revés. El separador es un espacio, no un prefijo pelado, así que
            // "Buzo" nunca matchea "Buzos".
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
        if ("desc_pct".equals(orden)) {
            // No es cosmético: el orden por descuento DESCARTA lo que no tiene
            // descuento, igual que el `.filter(tieneDescuento)` del comparador Java.
            cond.add("p.precio_orig IS NOT NULL AND btrim(p.precio_orig) <> ''");
        }

        return new Where(String.join("\n  AND ", cond), params);
    }

    private void agregarVisual(List<String> cond, List<Object> params, String columna, String valor) {
        if (!noVacio(valor)) return;
        cond.add("lower(coalesce(p." + columna + ",'')) = lower(?)");
        params.add(valor);
    }

    // ─── ORDER BY ───────────────────────────────────────────────────────────

    /**
     * {@code url} cierra todos los ORDER BY: SQL no tiene sort estable y sin
     * desempate una misma consulta puede devolver un producto en dos páginas
     * distintas (o en ninguna).
     */
    private String orderBy(String orden) {
        return switch (orden != null ? orden : "precio_asc") {
            case "precio_desc" -> "ORDER BY p.precio DESC, p.url ASC";
            case "nombre_asc", "nombre" -> "ORDER BY lower(coalesce(p.nombre,'')) ASC, p.url ASC";
            // Ascendente, igual que el comparador Java. Parece un bug (el peor
            // score primero) pero replicarlo es el punto de este cambio; si se
            // arregla, se arregla aparte y a la vista.
            case "composite", "ml_score" -> "ORDER BY p.ml_score ASC, p.url ASC";
            case "desc_pct" -> "ORDER BY " + PCT_DESCUENTO + " DESC, p.url ASC";
            default -> "ORDER BY p.precio ASC, p.url ASC";
        };
    }

    /**
     * Espejo del parseo Java de {@code precioOriginal}: se descartan los no
     * dígitos y lo que no queda como número se cuenta como 0 en vez de romper
     * (allá es un {@code catch} que devuelve 0.0).
     */
    private static final String PCT_DESCUENTO = """
            (CASE WHEN regexp_replace(coalesce(p.precio_orig,''), '[^0-9.]', '', 'g') ~ '^[0-9]+(\\.[0-9]*)?$'
                       AND regexp_replace(coalesce(p.precio_orig,''), '[^0-9.]', '', 'g')::double precision > 0
                  THEN (regexp_replace(p.precio_orig, '[^0-9.]', '', 'g')::double precision - p.precio)
                       / regexp_replace(p.precio_orig, '[^0-9.]', '', 'g')::double precision
                  ELSE 0.0 END)""";

    // ─── Ejecución ──────────────────────────────────────────────────────────

    private int contar(Connection c, Where where) throws SQLException {
        String sql = "SELECT COUNT(*) FROM productos p WHERE " + where.sql();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Cuatro sentencias por página, TODAS constantes en el tamaño del catálogo:
     * las urls de la página (con el filtro y el orden), los productos de esas
     * urls, y sus dos tablas hijas. El orden lo fija la primera query y se
     * reconstruye en Java — {@code WHERE url = ANY(...)} no conserva ninguno.
     */
    private List<Product> leerPagina(Connection c, Where where, String orden, int size, int offset)
            throws SQLException {
        List<String> urls = new ArrayList<>();
        String sqlUrls = "SELECT p.url FROM productos p WHERE " + where.sql()
                + "\n" + orderBy(orden) + "\nLIMIT ? OFFSET ?";
        try (PreparedStatement ps = c.prepareStatement(sqlUrls)) {
            int i = bind(c, ps, where.params());
            ps.setInt(i++, size);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) urls.add(rs.getString(1));
            }
        }
        if (urls.isEmpty()) return List.of();

        Map<String, List<String>> talles = multivalor(c, "producto_talle", "talle", urls);
        Map<String, List<String>> badges = multivalor(c, "producto_badge", "badge", urls);

        Map<String, Product> porUrl = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(ProductRowMapper.COLUMNAS + " p WHERE p.url = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("text", urls.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String url = rs.getString("url");
                    porUrl.put(url, ProductRowMapper.map(rs,
                            talles.getOrDefault(url, List.of()),
                            badges.getOrDefault(url, List.of())));
                }
            }
        }

        List<Product> pagina = new ArrayList<>(urls.size());
        for (String url : urls) {
            Product p = porUrl.get(url);
            if (p != null) pagina.add(p);
        }
        return pagina;
    }

    private Map<String, List<String>> multivalor(Connection c, String tabla, String columna, List<String> urls)
            throws SQLException {
        Map<String, List<String>> porUrl = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT url," + columna + " FROM " + tabla + " WHERE url = ANY(?) ORDER BY url, posicion")) {
            ps.setArray(1, c.createArrayOf("text", urls.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    porUrl.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        }
        return porUrl;
    }

    /** Marker so a text[] parameter survives the generic param list. */
    private record TextArray(List<String> valores) {
    }

    private int bind(Connection c, PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            if (p instanceof TextArray arr) {
                ps.setArray(i++, c.createArrayOf("text", arr.valores().toArray()));
            } else {
                ps.setObject(i++, p);
            }
        }
        return i;
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static boolean noVacio(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean noVacia(List<String> l) {
        return l != null && !l.isEmpty();
    }

    private static List<String> enMinusculas(List<String> valores) {
        List<String> out = new ArrayList<>(valores.size());
        for (String v : valores) out.add(v != null ? v.toLowerCase(Locale.ROOT) : "");
        return out;
    }

    /** LIKE/ILIKE tratan % y _ como comodines; el filtro Java hacía substring literal. */
    private static String escaparLike(String valor) {
        return valor.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> escaparLike(List<String> valores) {
        List<String> out = new ArrayList<>(valores.size());
        for (String v : valores) out.add(escaparLike(v));
        return out;
    }
}
