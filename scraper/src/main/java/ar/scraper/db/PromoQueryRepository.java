package ar.scraper.db;

import ar.scraper.catalog.ProductoRef;
import ar.scraper.catalog.PromoPagina;
import ar.scraper.catalog.PromoQueryPort;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoVigente;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;

/**
 * Writes are NOT here: they happen inside the run's product upsert ({@code sp_upsert_promos}).
 * "Current" is {@code activo} and today (Buenos Aires) inside the vigencia window; a promo with no
 * dates is always inside it.
 */
@Repository
class PromoQueryRepository implements PromoQueryPort {

    private static final String HOY = "(now() AT TIME ZONE 'America/Argentina/Buenos_Aires')::date";

    private static final String COLUMNAS = """
            s.nombre, m.nombre, m.tipo, r.nombre, pf.tipo, pf.cuotas, pf.pct, pf.alcance,
            pf.vigencia_desde, pf.vigencia_hasta, pf.texto_fuente, pf.visto_at""";

    private static final String DESDE = """
            FROM promo_financiacion pf
            JOIN sitio s      ON s.sitio_key = pf.sitio_key
            JOIN medio_pago m ON m.id = pf.medio_pago_id
            LEFT JOIN medio_pago r ON r.id = pf.red_medio_pago_id""";

    private static final String VIGENTE = " pf.activo AND (pf.vigencia_desde IS NULL OR pf.vigencia_desde <= " + HOY
            + ") AND (pf.vigencia_hasta IS NULL OR pf.vigencia_hasta >= " + HOY + ")";

    /** A discount first, then installments, then a surcharge; the same order the detail applies in Java. */
    private static final String ORDEN = """
             ORDER BY s.nombre,
                      CASE pf.tipo WHEN 'descuento' THEN 0 WHEN 'cuotas_sin_interes' THEN 1 ELSE 2 END,
                      COALESCE(pf.pct, 0) DESC, COALESCE(pf.cuotas, 0) DESC, m.nombre, pf.id""";

    private static final RowMapper<PromoVigente> FILA = PromoQueryRepository::mapear;

    private final JdbcTemplate jdbc;

    PromoQueryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public PromoPagina buscar(String sitio, String medio, int page, int size) {
        StringBuilder where = new StringBuilder(VIGENTE);
        List<Object> args = new ArrayList<>();
        if (StringUtils.isNotBlank(sitio)) {
            where.append(" AND s.nombre = ?");
            args.add(sitio.trim());
        }
        if (StringUtils.isNotBlank(medio)) {
            where.append(" AND (lower(m.nombre) = lower(?) OR lower(r.nombre) = lower(?))");
            args.add(medio.trim());
            args.add(medio.trim());
        }
        Long total = jdbc.queryForObject("SELECT count(*) " + DESDE + " WHERE" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<PromoVigente> promos = jdbc.query(
                "SELECT " + COLUMNAS + " " + DESDE + " WHERE" + where + " " + ORDEN + " LIMIT ? OFFSET ?", FILA, args.toArray());
        return new PromoPagina(promos, total == null ? 0 : total);
    }

    @Override
    public Map<String, List<PromoVigente>> paraProductos(List<ProductoRef> productos) {
        if (productos.isEmpty()) return Map.of();
        Set<String> sitios = new TreeSet<>();
        List<String> urls = new ArrayList<>(productos.size());
        for (ProductoRef p : productos) {
            sitios.add(p.sitio());
            urls.add(p.url());
        }

        // Two queries whatever the page size: the page's sites (site-scoped) and its urls (product-scoped). A single
        // UNION ALL round trip was measured and was not faster.
        Map<String, List<PromoVigente>> delSitio = new HashMap<>();
        Map<String, List<PromoVigente>> delProducto = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLUMNAS + " " + DESDE
                    + " WHERE" + VIGENTE + " AND pf.alcance = 'sitio' AND s.nombre = ANY (?)");
            ps.setArray(1, con.createArrayOf("text", sitios.toArray()));
            return ps;
        }, rs -> {
            PromoVigente p = mapear(rs, 0);
            delSitio.computeIfAbsent(p.sitio(), k -> new ArrayList<>()).add(p);
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLUMNAS + ", l.url " + DESDE
                    + " JOIN promo_financiacion_producto l ON l.promo_id = pf.id"
                    + " WHERE" + VIGENTE + " AND pf.alcance = 'producto' AND l.url = ANY (?)");
            ps.setArray(1, con.createArrayOf("text", urls.toArray()));
            return ps;
        }, rs -> {
            PromoVigente p = mapear(rs, 0);
            delProducto.computeIfAbsent(rs.getString(13), k -> new ArrayList<>()).add(p);
        });

        Map<String, List<PromoVigente>> resultado = new LinkedHashMap<>();
        for (ProductoRef p : productos) {
            List<PromoVigente> suyas = new ArrayList<>(delProducto.getOrDefault(p.url(), List.of()));
            suyas.addAll(delSitio.getOrDefault(p.sitio(), List.of()));
            if (!suyas.isEmpty()) resultado.put(p.url(), suyas);
        }
        return resultado;
    }

    private static PromoVigente mapear(ResultSet rs, int fila) throws SQLException {
        int cuotas = rs.getInt(6);
        Integer cuotasOrNull = rs.wasNull() ? null : cuotas;
        var desde = rs.getObject(9, java.time.LocalDate.class);
        var hasta = rs.getObject(10, java.time.LocalDate.class);
        return new PromoVigente(rs.getString(1), rs.getString(2),
                TipoMedio.deSql(rs.getString(3)).orElse(TipoMedio.MEDIO), rs.getString(4),
                tipo(rs.getString(5)), cuotasOrNull, rs.getBigDecimal(7), alcance(rs.getString(8)), desde, hasta,
                rs.getString(11), rs.getObject(12, java.time.OffsetDateTime.class).toInstant());
    }

    private static TipoPromo tipo(String sql) {
        for (TipoPromo t : TipoPromo.values()) if (t.sql().equals(sql)) return t;
        throw new IllegalStateException("promo_financiacion.tipo desconocido: " + sql);
    }

    private static Alcance alcance(String sql) {
        for (Alcance a : Alcance.values()) if (a.sql().equals(sql)) return a;
        throw new IllegalStateException("promo_financiacion.alcance desconocido: " + sql);
    }
}
