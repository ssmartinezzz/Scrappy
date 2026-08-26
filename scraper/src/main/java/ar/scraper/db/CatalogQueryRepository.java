package ar.scraper.db;

import ar.scraper.aggregator.normalize.SiteRegistry;
import ar.scraper.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    private final DataSource dataSource;
    private final SiteRegistry siteRegistry;

    CatalogQueryRepository(DataSource dataSource, SiteRegistry siteRegistry) {
        this.dataSource = dataSource;
        this.siteRegistry = siteRegistry;
    }

    CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size) {
        return buscar(filtro, orden, page, size, Optional.empty());
    }

    /** @param desde the run's {@code started_at}; empty serves the whole catalogue. */
    CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size, Optional<Instant> desde) {
        Where where = construirWhere(filtro, cotaDe(desde));
        try (Connection c = dataSource.getConnection()) {
            int total = contar(c, where);
            int paginaClamped = Math.max(page, 1);
            int offset = (int) Math.min((long) (paginaClamped - 1) * size, Math.max(total, 0));

            List<Product> productos = leerPagina(c, where, orden, size, offset);
            return new CatalogPage(productos, total);
        } catch (Exception e) {
            LOG.error("[DB] Error consultando el catálogo: {}", e.getMessage(), e);
            return new CatalogPage(List.of(), 0);
        }
    }

    /**
     * Las facetas del catálogo activo, con un GROUP BY por faceta en vez de un
     * barrido en memoria de las 13543 filas.
     *
     * <p>Cada consulta aplica en SQL la MISMA normalización de clave que hacía
     * {@code FacetCalculator} (trim, lower para género, capitalizar la primera
     * letra en categoría) para que las claves salgan idénticas. El orden
     * también se replica: categorías y marcas por conteo descendente, marcas
     * limitadas a 30, subcategorías por clave, y los talles por
     * {@link ar.scraper.aggregator.FacetCalculator#sortTalles} — que se reusa,
     * no se reimplementa.</p>
     *
     * <p>Un cambio visible y deliberado: género, badges y los cuatro atributos
     * visuales salían en "orden de primera aparición" dentro de un catálogo
     * ordenado por precio, que no es un orden sino un accidente. Ahora salen
     * por conteo descendente.</p>
     */
    ar.scraper.aggregator.ResultAggregator.Facets facetas() {
        return facetas(Optional.empty());
    }

    /** @param desde the run's {@code started_at}; empty counts the whole catalogue. */
    ar.scraper.aggregator.ResultAggregator.Facets facetas(Optional<Instant> desde) {
        Cota cota = cotaDe(desde);
        try (Connection c = dataSource.getConnection()) {
            Map<String, Long> talles = ar.scraper.aggregator.FacetCalculator.sortTalles(
                    contarHija(c, "producto_talle", "talle", cota));
            Map<String, Long> badges = contarHija(c, "producto_badge", "badge", cota);

            Map<String, Long> generos = contar(c, "lower(btrim(genero))", cota);
            Map<String, Long> categorias = contar(c,
                    "upper(left(btrim(categoria),1)) || lower(substr(btrim(categoria),2))", cota);
            Map<String, Long> marcas = limitar(contar(c, "btrim(marca)", cota), 30);
            Map<String, Long> subCategorias = ordenarPorClave(contar(c, "btrim(sub_categoria)", cota));
            Map<String, Long> fits = contar(c, "btrim(fit)", cota);
            Map<String, Long> estampados = contar(c, "btrim(estampado)", cota);
            Map<String, Long> escotes = contar(c, "btrim(escote)", cota);
            Map<String, Long> colores = contar(c, "btrim(color_dominante)", cota);

            return new ar.scraper.aggregator.ResultAggregator.Facets(
                    talles, generos, categorias, marcas, badges, subCategorias,
                    fits, estampados, escotes, colores);
        } catch (Exception e) {
            LOG.error("[DB] Error calculando facetas: {}", e.getMessage(), e);
            return new ar.scraper.aggregator.ResultAggregator.Facets(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    CatalogResumen resumen() {
        return resumen(Optional.empty());
    }

    /**
     * @param desde the run's {@code started_at}; empty summarises the whole
     *              catalogue. Bounding this and leaving it out of {@code buscar}
     *              — or the reverse — is what makes the 204 check and the page
     *              contents disagree, so both go through the same {@link Cota}.
     */
    CatalogResumen resumen(Optional<Instant> desde) {
        Cota cota = cotaDe(desde);
        double min = 0, max = 0;
        long conteoGymrat = 0, conteoPacks = 0;
        int total = 0;
        Map<String, Long> porSitio = new java.util.LinkedHashMap<>();
        Map<String, Long> rubros = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT coalesce(min(precio),0), coalesce(max(precio),0), COUNT(*), "
                            + "count(*) FILTER (WHERE gymrat), count(*) FILTER (WHERE cantidad_unidades > 1) "
                            + "FROM productos WHERE activo" + cota.sqlAnd(""))) {
                cota.bind(ps, 1);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    min = rs.getDouble(1);
                    max = rs.getDouble(2);
                    total = rs.getInt(3);
                    conteoGymrat = rs.getLong(4);
                    conteoPacks = rs.getLong(5);
                }
            }
            porSitio = contar(c, "sitio", cota);
            rubros = contar(c, "coalesce(nullif(btrim(rubro),''),'indumentaria')", cota);
        } catch (Exception e) {
            LOG.error("[DB] Error calculando el resumen del catálogo: {}", e.getMessage(), e);
        }
        return new CatalogResumen(min, max, porSitio, rubros, conteoGymrat, conteoPacks, total);
    }

    /** GROUP BY sobre una expresión de `productos`, descartando el blanco, por conteo descendente. */
    private Map<String, Long> contar(Connection c, String expresion, Cota cota) throws SQLException {
        Map<String, Long> conteo = new java.util.LinkedHashMap<>();
        String sql = "SELECT " + expresion + " AS clave, COUNT(*) FROM productos "
                + "WHERE activo AND coalesce(btrim(" + expresion + "), '') <> ''"
                + cota.sqlAnd("")
                + " GROUP BY 1 ORDER BY 2 DESC, 1 ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            cota.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) conteo.put(rs.getString(1), rs.getLong(2));
            }
        }
        return conteo;
    }

    /**
     * Igual pero sobre una tabla hija: un producto cuenta UNA VEZ POR VALOR que
     * tiene, no una sola vez — es la semántica multi-badge que la spec pide.
     */
    private Map<String, Long> contarHija(Connection c, String tabla, String columna, Cota cota)
            throws SQLException {
        Map<String, Long> conteo = new java.util.LinkedHashMap<>();
        // The bound qualifies `p`, the parent: a child row is in scope exactly
        // when its product is. Leaving this one unbounded is invisible from
        // `buscar` — the page shrinks correctly while the talles and badges
        // filters keep offering values only the held-back products carry.
        String sql = "SELECT btrim(h." + columna + ") AS clave, COUNT(*) FROM " + tabla + " h "
                + "JOIN productos p ON p.url = h.url "
                + "WHERE p.activo AND btrim(h." + columna + ") <> ''"
                + cota.sqlAnd("p.")
                + " GROUP BY 1 ORDER BY 2 DESC, 1 ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            cota.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) conteo.put(rs.getString(1), rs.getLong(2));
            }
        }
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

    // ─── WHERE ──────────────────────────────────────────────────────────────

    /** SQL fragment plus its bound parameters, in order. */
    private record Where(String sql, List<Object> params) {
    }

    /**
     * The reader bound: while a run is in flight, hold back the rows it has
     * already re-touched so a reader sees the catalogue as it stood before the
     * run started, instead of a half-rescraped mix (design D1/D6).
     *
     * <p><b>Absent means serve everything</b>, never "bound = epoch, serve
     * nothing". A fresh install with no completed run behind it must show its
     * progress, not an empty screen.</p>
     *
     * <p><b>Exclusive on purpose, and the mirror image of the soft-delete
     * union.</b> {@code touched_at} only ever holds whole seconds and
     * {@code ScrapeRunRepository.crear} truncates {@code started_at} to match,
     * so a row written during the run's own first second compares equal and
     * fails {@code <} — it is held back. That is a row hidden one second early,
     * before any site can plausibly have finished, and it loses nobody any data.
     * The sweep's bound is {@code >=} because it must protect rows from being
     * deleted; this one is {@code <} because it may hide a fresh row. The two
     * directions are deliberate and opposite.</p>
     *
     * <p>Rendering and binding live together here on purpose: the predicate has
     * to reach <b>four</b> separate {@code activo} clauses — {@code construirWhere},
     * {@code resumen}'s aggregate, the {@code contar(String)} GROUP BY overload
     * and {@code contarHija}'s JOIN. Four hand-written copies is four chances to
     * miss one, and missing one is invisible from {@code buscar}: the page shrinks
     * correctly while the facets keep advertising values it cannot show.</p>
     */
    private record Cota(Optional<Instant> desde) {

        static final Cota SIN_COTA = new Cota(Optional.empty());

        /** {@code " AND <alias>touched_at < ?"}, or nothing when absent. */
        String sqlAnd(String alias) {
            return desde.isEmpty() ? "" : " AND " + alias + "touched_at < ?";
        }

        /** Binds this bound's parameter, if any, and returns the next free index. */
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
            // DESC, no ASC. El filtro en memoria ordenaba ascendente, o sea que
            // el selector rotulado "ML Score" en el frontend mostraba PRIMERO los
            // peores scores. Arreglado acá y no replicado: acarrearlo a SQL era
            // convertir un bug de UI en un bug de esquema.
            case "composite", "ml_score" -> "ORDER BY p.ml_score DESC, p.url ASC";
            // Ordena, no filtra. El comparador en memoria descartaba los productos
            // sin descuento DENTRO del sort, así que cambiar el orden cambiaba el
            // total y la paginación — total sigue sin cambiar acá.
            //
            // NULLS LAST (D6, V17): precio_orig ya es double precision, así que
            // PCT_DESCUENTO es una resta/división que propaga NULL sola cuando
            // no hay precio original — "no sé" nunca cae al final del sort
            // travestido de "0% de descuento" (el bug real: un descuento
            // NEGATIVO conocido terminaba ordenado DESPUÉS de un precio_orig
            // NULL, porque 0.0 > cualquier negativo). Ver CatalogOrdenTest.
            case "desc_pct" -> "ORDER BY " + PCT_DESCUENTO + " DESC NULLS LAST, p.url ASC";
            default -> "ORDER BY p.precio ASC, p.url ASC";
        };
    }

    /** precio_orig es double precision desde V17 — sin parseo, sin regex. */
    private static final String PCT_DESCUENTO = "((p.precio_orig - p.precio) / p.precio_orig)";

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
                            badges.getOrDefault(url, List.of()), siteRegistry));
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
