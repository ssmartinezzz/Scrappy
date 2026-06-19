package ar.scraper.db;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SQLite persistence layer.
 *
 * Archivo: scraper.db en el directorio de trabajo (junto al .jar)
 *
 * Upsert logic:
 *   - Producto nuevo  → INSERT
 *   - Precio igual    → UPDATE touched_at solamente
 *   - Precio cambió   → UPDATE precio + INSERT en precio_historico
 *   - No apareció     → soft-delete (activo=0)
 */
@Service
public class DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HIST_DAYS = 90;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Connection conn;

    @PostConstruct
    public void init() {
        try {
            String path = Paths.get("scraper.db").toAbsolutePath().toString();
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            crearTablas();
            conn.commit();
            LOG.info("[DB] Conectado: {}", path);
        } catch (Exception e) {
            LOG.error("[DB] Error al iniciar: {}", e.getMessage(), e);
        }
    }

    // ─── Schema ──────────────────────────────────────────────────────────────

    /**
     * Migración aditiva idempotente: intenta agregar una columna a una tabla
     * existente. SQLite no soporta "ADD COLUMN IF NOT EXISTS", así que se
     * intenta y se ignora el error de "duplicate column" si ya existe.
     */
    private void migrarColumna(Statement st, String tabla, String colDef) {
        try {
            st.executeUpdate("ALTER TABLE " + tabla + " ADD COLUMN " + colDef);
            LOG.info("[DB] Migración: columna agregada en {} -> {}", tabla, colDef);
        } catch (SQLException e) {
            LOG.debug("[DB] Columna ya existe en {} ({})", tabla, e.getMessage());
        }
    }

    private void crearTablas() throws SQLException {
        try (Statement st = conn.createStatement()) {
st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precios_externos (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    producto_url TEXT NOT NULL,
                    sitio        TEXT NOT NULL,
                    titulo       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    externo_url  TEXT,
                    condicion    TEXT DEFAULT 'new',
                    fecha        TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pe_url ON precios_externos(producto_url)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS productos (
                    url          TEXT PRIMARY KEY,
                    sitio        TEXT NOT NULL,
                    nombre       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    precio_orig  TEXT,
                    imagen_url   TEXT,
                    categoria    TEXT,
                    genero       TEXT,
                    talles       TEXT,
                    ml_badge     TEXT DEFAULT '',
                    ml_score     INTEGER DEFAULT 50,
                    ml_oferta    INTEGER DEFAULT 0,
                    ml_tendencia TEXT DEFAULT '',
                    ml_segment   TEXT DEFAULT 'standard',
                    ml_zscore    REAL DEFAULT 0.0,
                    rubro        TEXT DEFAULT 'indumentaria',
                    marca        TEXT DEFAULT '',
                    gymrat       INTEGER DEFAULT 0,
                    marca_premium INTEGER DEFAULT 0,
                    activo       INTEGER DEFAULT 1,
                    touched_at   TEXT,
                    created_at   TEXT
                )""");

            migrarColumna(st, "productos", "gymrat INTEGER DEFAULT 0");
            migrarColumna(st, "productos", "marca_premium INTEGER DEFAULT 0");

st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precios_externos (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    producto_url TEXT NOT NULL,
                    sitio        TEXT NOT NULL,
                    titulo       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    externo_url  TEXT,
                    condicion    TEXT DEFAULT 'new',
                    fecha        TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pe_url ON precios_externos(producto_url)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precio_historico (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    url     TEXT NOT NULL,
                    precio  REAL NOT NULL,
                    fecha   TEXT NOT NULL,
                    UNIQUE(url, fecha)
                )""");

st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precios_externos (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    producto_url TEXT NOT NULL,
                    sitio        TEXT NOT NULL,
                    titulo       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    externo_url  TEXT,
                    condicion    TEXT DEFAULT 'new',
                    fecha        TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pe_url ON precios_externos(producto_url)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ml_output (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    payload    TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");

st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precios_externos (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    producto_url TEXT NOT NULL,
                    sitio        TEXT NOT NULL,
                    titulo       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    externo_url  TEXT,
                    condicion    TEXT DEFAULT 'new',
                    fecha        TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pe_url ON precios_externos(producto_url)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sitios_dinamicos (
                    nombre     TEXT PRIMARY KEY,
                    url        TEXT NOT NULL,
                    plataforma TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");

            // Indices
st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS categoria_stats (
                    categoria  TEXT PRIMARY KEY,
                    payload    TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_prod_sitio  ON productos(sitio)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_prod_activo ON productos(activo)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_hist_url   ON precio_historico(url)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS favoritos (
                    url             TEXT PRIMARY KEY,
                    sitio           TEXT NOT NULL,
                    nombre          TEXT,
                    added_at        TEXT,
                    last_checked_at TEXT
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fav_sitio ON favoritos(sitio)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS outfit_feedback (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    genero        TEXT,
                    liked         INTEGER NOT NULL DEFAULT 0,
                    torso_url     TEXT,
                    piernas_url   TEXT,
                    calzado_url   TEXT,
                    accesorio_url TEXT,
                    created_at    TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_outfit_fb_liked ON outfit_feedback(liked)");
        }
    }

    // ─── Upsert de productos ─────────────────────────────────────────────────

    /**
     * Aplica la lógica de merge al dataset completo de un scraping.
     * Retorna estadísticas: {nuevos, actualizados, sinCambios, desactivados}
     */
    public UpsertStats upsertProductos(List<Product> productos) {
        if (conn == null) return new UpsertStats(0, 0, 0, 0);

        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);

        int nuevos = 0, actualizados = 0, sinCambios = 0;

        try {
            // 1. Obtener precios actuales de todos los productos activos
            Map<String, Double> preciosActuales = getPreciosActuales();
            Set<String> urlsNuevoRun = new HashSet<>();

            String upsertSql = """
                INSERT INTO productos
                    (url,sitio,nombre,precio,precio_orig,imagen_url,categoria,genero,
                     talles,ml_badge,ml_score,ml_oferta,ml_tendencia,ml_segment,ml_zscore,
                     rubro,marca,gymrat,marca_premium,activo,touched_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                ON CONFLICT(url) DO UPDATE SET
                    sitio        = excluded.sitio,
                    nombre       = excluded.nombre,
                    precio       = excluded.precio,
                    precio_orig  = excluded.precio_orig,
                    imagen_url   = excluded.imagen_url,
                    categoria    = excluded.categoria,
                    genero       = excluded.genero,
                    talles       = excluded.talles,
                    ml_badge     = excluded.ml_badge,
                    ml_score     = excluded.ml_score,
                    ml_oferta    = excluded.ml_oferta,
                    ml_tendencia = excluded.ml_tendencia,
                    ml_segment   = excluded.ml_segment,
                    ml_zscore    = excluded.ml_zscore,
                    rubro        = excluded.rubro,
                    marca        = excluded.marca,
                    gymrat       = excluded.gymrat,
                    marca_premium = excluded.marca_premium,
                    activo       = 1,
                    touched_at   = excluded.touched_at
                """;

            String histSql = """
                INSERT OR IGNORE INTO precio_historico (url, precio, fecha)
                VALUES (?, ?, ?)
                """;

            try (PreparedStatement psUpsert = conn.prepareStatement(upsertSql);
                 PreparedStatement psHist   = conn.prepareStatement(histSql)) {

                for (Product p : productos) {
                    if (p.url() == null || p.url().isBlank()) continue;
                    urlsNuevoRun.add(p.url());

                    String tallesJson = MAPPER.writeValueAsString(
                            p.talles() != null ? p.talles() : List.of());

                    // ML fields
                    String badge    = p.ml() != null ? p.ml().badge()         : "";
                    int    score    = p.ml() != null ? p.ml().scoreP()        : 50;
                    int    oferta   = (p.ml() != null && p.ml().ofertaReal()) ? 1 : 0;
                    String tendencia = p.ml() != null ? p.ml().tendencia()    : "";

                    psUpsert.setString(1,  p.url());
                    psUpsert.setString(2,  p.sitio());
                    psUpsert.setString(3,  p.nombre());
                    psUpsert.setDouble(4,  p.precio());
                    psUpsert.setString(5,  p.precioOriginal());
                    psUpsert.setString(6,  p.imagenUrl());
                    psUpsert.setString(7,  p.categoria());
                    psUpsert.setString(8,  p.genero());
                    psUpsert.setString(9,  tallesJson);
                    psUpsert.setString(10, badge);
                    psUpsert.setInt   (11, score);
                    psUpsert.setInt   (12, oferta);
                    psUpsert.setString(13, tendencia);
                    psUpsert.setString(14, p.ml() != null ? p.ml().segment() : "standard");
                    psUpsert.setDouble(15, p.ml() != null ? p.ml().zScore() : 0.0);
                    psUpsert.setString(16, p.rubro() != null ? p.rubro() : "indumentaria");
                    psUpsert.setString(17, p.marca() != null ? p.marca() : "");
                    psUpsert.setInt   (18, p.gymrat() ? 1 : 0);
                    psUpsert.setInt   (19, p.marcaPremium() ? 1 : 0);
                    psUpsert.setString(20, now);   // touched_at
                    psUpsert.setString(21, now);   // created_at
                    psUpsert.executeUpdate();

                    Double prevPrecio = preciosActuales.get(p.url());
                    if (prevPrecio == null) {
                        // Producto nuevo
                        nuevos++;
                        psHist.setString(1, p.url());
                        psHist.setDouble(2, p.precio());
                        psHist.setString(3, today);
                        psHist.executeUpdate();
                    } else if (Math.abs(prevPrecio - p.precio()) > 0.01) {
                        // Precio cambió
                        actualizados++;
                        psHist.setString(1, p.url());
                        psHist.setDouble(2, p.precio());
                        psHist.setString(3, today);
                        psHist.executeUpdate();
                    } else {
                        sinCambios++;
                    }
                }
            }

            // 2. Soft-delete: productos en DB que no aparecieron en este run
            int desactivados = softDeleteAusentes(urlsNuevoRun, now);

            // 3. Purgar historial > 90 días
            purgarHistorialViejo();

            conn.commit();

            LOG.info("[DB] Upsert: {} nuevos / {} precio cambió / {} sin cambio / {} desactivados",
                    nuevos, actualizados, sinCambios, desactivados);
            return new UpsertStats(nuevos, actualizados, sinCambios, desactivados);

        } catch (Exception e) {
            LOG.error("[DB] Error en upsert: {}", e.getMessage(), e);
            try { conn.rollback(); } catch (Exception ignored) {}
            return new UpsertStats(0, 0, 0, 0);
        }
    }

    private Map<String, Double> getPreciosActuales() throws SQLException {
        Map<String, Double> map = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT url, precio FROM productos WHERE activo=1")) {
            while (rs.next()) map.put(rs.getString(1), rs.getDouble(2));
        }
        return map;
    }

    private int softDeleteAusentes(Set<String> urlsPresentes, String now) throws SQLException {
        // Traer todos los urls activos
        List<String> activos = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT url FROM productos WHERE activo=1")) {
            while (rs.next()) activos.add(rs.getString(1));
        }
        List<String> ausentes = activos.stream()
                .filter(u -> !urlsPresentes.contains(u))
                .toList();
        if (ausentes.isEmpty()) return 0;

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE productos SET activo=0, touched_at=? WHERE url=?")) {
            for (String url : ausentes) {
                ps.setString(1, now);
                ps.setString(2, url);
                ps.executeUpdate();
            }
        }
        return ausentes.size();
    }

    private void purgarHistorialViejo() throws SQLException {
        String cutoff = LocalDate.now().minusDays(MAX_HIST_DAYS).format(DATE);
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM precio_historico WHERE fecha < ? " +
                "AND url NOT IN (SELECT url FROM favoritos)")) {
            ps.setString(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) LOG.debug("[DB] Purged {} entradas historial > 90 dias", deleted);
        }
    }


    /**
     * Upsert parcial durante scraping progresivo.
     * NUNCA hace soft-delete — solo inserta/actualiza los productos dados.
     * El soft-delete lo hace upsertProductos() al final del run completo.
     */
    public void upsertParcial(List<Product> productos) {
        if (conn == null || productos.isEmpty()) return;
        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);
        try {
            String upsertSql = """
                INSERT INTO productos
                    (url,sitio,nombre,precio,precio_orig,imagen_url,categoria,genero,
                     talles,ml_badge,ml_score,ml_oferta,ml_tendencia,ml_segment,ml_zscore,
                     rubro,marca,gymrat,marca_premium,activo,touched_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                ON CONFLICT(url) DO UPDATE SET
                    sitio        = excluded.sitio,
                    nombre       = excluded.nombre,
                    precio       = excluded.precio,
                    precio_orig  = excluded.precio_orig,
                    imagen_url   = excluded.imagen_url,
                    categoria    = excluded.categoria,
                    genero       = excluded.genero,
                    talles       = excluded.talles,
                    rubro        = excluded.rubro,
                    marca        = excluded.marca,
                    gymrat       = excluded.gymrat,
                    marca_premium = excluded.marca_premium,
                    activo       = 1,
                    touched_at   = excluded.touched_at
                """;
            String histSql = "INSERT OR IGNORE INTO precio_historico (url,precio,fecha) VALUES(?,?,?)";
            Map<String,Double> prevPrecios = getPreciosActuales();

            try (PreparedStatement psU = conn.prepareStatement(upsertSql);
                 PreparedStatement psH = conn.prepareStatement(histSql)) {
                for (Product p : productos) {
                    if (p.url() == null || p.url().isBlank()) continue;
                    String tallesJson = MAPPER.writeValueAsString(p.talles() != null ? p.talles() : List.of());
                    psU.setString(1,  p.url());
                    psU.setString(2,  p.sitio());
                    psU.setString(3,  p.nombre());
                    psU.setDouble(4,  p.precio());
                    psU.setString(5,  p.precioOriginal() != null ? p.precioOriginal() : "");
                    psU.setString(6,  p.imagenUrl()      != null ? p.imagenUrl()      : "");
                    psU.setString(7,  p.categoria()      != null ? p.categoria()      : "");
                    psU.setString(8,  p.genero()         != null ? p.genero()         : "");
                    psU.setString(9,  tallesJson);
                    psU.setString(10, "");                        // ml_badge
                    psU.setInt   (11, 50);                        // ml_score
                    psU.setInt   (12, 0);                         // ml_oferta
                    psU.setString(13, "");                        // ml_tendencia
                    psU.setString(14, "standard");                // ml_segment
                    psU.setDouble(15, 0.0);                       // ml_zscore
                    psU.setString(16, p.rubro() != null ? p.rubro() : "indumentaria"); // rubro
                    psU.setString(17, p.marca() != null ? p.marca() : "");             // marca
                    psU.setInt   (18, p.gymrat() ? 1 : 0);                             // gymrat
                    psU.setInt   (19, p.marcaPremium() ? 1 : 0);                       // marca_premium
                    psU.setString(20, now);                       // touched_at
                    psU.setString(21, now);                       // created_at
                    psU.executeUpdate();
                    Double prev = prevPrecios.get(p.url());
                    if (prev == null || Math.abs(prev - p.precio()) > 0.01) {
                        psH.setString(1, p.url());
                        psH.setDouble(2, p.precio());
                        psH.setString(3, today);
                        psH.executeUpdate();
                    }
                }
            }
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    // ─── Cargar productos ────────────────────────────────────────────────────

    public List<Product> cargarProductos() {
        if (conn == null) return List.of();
        List<Product> result = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT url,sitio,nombre,precio,precio_orig,imagen_url," +
                "categoria,genero,talles,ml_badge,ml_score,ml_oferta,ml_tendencia," +
                "ml_segment,ml_zscore,rubro,marca,gymrat,marca_premium " +
                "FROM productos WHERE activo=1 ORDER BY precio ASC")) {
            while (rs.next()) {
                result.add(productoDesdeFila(rs));
            }
            LOG.info("[DB] Cargados {} productos activos", result.size());
        } catch (Exception e) {
            LOG.error("[DB] Error cargando productos: {}", e.getMessage(), e);
        }
        return result;
    }

    /** Busca un producto por URL sin filtrar por `activo` (incluye descontinuados). */
    public java.util.Optional<Product> obtenerProducto(String url) {
        if (conn == null) return java.util.Optional.empty();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT url,sitio,nombre,precio,precio_orig,imagen_url," +
                "categoria,genero,talles,ml_badge,ml_score,ml_oferta,ml_tendencia," +
                "ml_segment,ml_zscore,rubro,marca,gymrat,marca_premium FROM productos WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(productoDesdeFila(rs));
            }
        } catch (Exception e) {
            LOG.error("[DB] Error obteniendo producto {}: {}", url, e.getMessage(), e);
            return java.util.Optional.empty();
        }
    }

    private Product productoDesdeFila(ResultSet rs) throws java.sql.SQLException {
        List<String> talles = List.of();
        try {
            JsonNode arr = MAPPER.readTree(rs.getString("talles"));
            if (arr.isArray()) {
                List<String> t = new ArrayList<>();
                arr.forEach(n -> t.add(n.asText()));
                talles = t;
            }
        } catch (Exception ignored) {}

        Product.MlScore ml = new Product.MlScore(
                rs.getInt("ml_score"),
                rs.getString("ml_badge")     != null ? rs.getString("ml_badge")     : "",
                rs.getInt("ml_oferta") == 1,
                rs.getString("ml_tendencia") != null ? rs.getString("ml_tendencia") : "",
                rs.getInt("ml_score"),
                rs.getDouble("ml_zscore"),
                rs.getString("ml_segment")   != null ? rs.getString("ml_segment")   : "standard"
        );

        String marca   = rs.getString("marca");
        String rubro   = rs.getString("rubro");
        boolean gymrat = rs.getInt("gymrat") == 1;
        boolean marcaPremium = rs.getInt("marca_premium") == 1;
        return new Product(
                rs.getString("sitio"), rs.getString("nombre"),
                rs.getDouble("precio"), rs.getString("precio_orig"),
                rs.getString("url"), rs.getString("imagen_url"),
                rs.getString("categoria"), rs.getString("genero"),
                talles, ml, marca != null ? marca : "",
                rubro != null && !rubro.isBlank() ? rubro : "indumentaria",
                gymrat, marcaPremium, Product.SenalCompra.EMPTY);
    }

    // ─── ML Output ──────────────────────────────────────────────────────────

    public void guardarMlOutput(JsonNode mlOutput) {
        if (conn == null || mlOutput == null) return;
        if (!esMlOutputValido(mlOutput)) {
            LOG.debug("[DB] ML output inválido (sin scores/tendencias) — no se persiste");
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(mlOutput);
            String now  = LocalDateTime.now().format(DT);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ml_output (payload, created_at) VALUES (?, ?)")) {
                ps.setString(1, json);
                ps.setString(2, now);
                ps.executeUpdate();
            }
            // Mantener solo los últimos 10 outputs
            try (Statement st = conn.createStatement()) {
    st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precios_externos (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    producto_url TEXT NOT NULL,
                    sitio        TEXT NOT NULL,
                    titulo       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    externo_url  TEXT,
                    condicion    TEXT DEFAULT 'new',
                    fecha        TEXT NOT NULL
                )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pe_url ON precios_externos(producto_url)");
            st.executeUpdate("""
                    DELETE FROM ml_output WHERE id NOT IN (
                        SELECT id FROM ml_output ORDER BY id DESC LIMIT 10
                    )""");
            }
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando ML output: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    /**
     * VALID == tiene un nodo {@code scores} objeto no vacío Y un nodo {@code tendencias}
     * presente como objeto. Mismo criterio usado por {@code ApiController.tendencias()}
     * (R1/R3) — garantiza que todo lo que se persiste, se puede servir.
     */
    private boolean esMlOutputValido(JsonNode ml) {
        if (ml == null || ml.isNull() || !ml.isObject()) return false;
        JsonNode scores = ml.path("scores");
        if (!scores.isObject() || scores.isEmpty()) return false;
        JsonNode tend = ml.path("tendencias");
        return tend.isObject();
    }

    public JsonNode cargarMlOutput() {
        if (conn == null) return null;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT payload FROM ml_output ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) {
                return MAPPER.readTree(rs.getString(1));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando ML output: {}", e.getMessage());
        }
        return null;
    }

    // ─── Historial de precios ────────────────────────────────────────────────

    public List<Map<String, Object>> cargarHistorial(String url) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (conn == null) return result;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha ASC")) {
            ps.setString(1, url);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fecha",  rs.getString(1));
                row.put("precio", rs.getDouble(2));
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error historial: {}", e.getMessage());
        }
        return result;
    }

    // ─── Sitios dinámicos ────────────────────────────────────────────────────

    public void guardarSitio(String nombre, String url, String plataforma) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO sitios_dinamicos (nombre, url, plataforma, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(nombre) DO UPDATE SET url=excluded.url, plataforma=excluded.plataforma
                """)) {
            ps.setString(1, nombre);
            ps.setString(2, url);
            ps.setString(3, plataforma);
            ps.setString(4, LocalDateTime.now().format(DT));
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando sitio: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public void eliminarSitio(String nombre) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sitios_dinamicos WHERE nombre=?")) {
            ps.setString(1, nombre);
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando sitio: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public List<Map<String, String>> cargarSitiosDinamicos() {
        List<Map<String, String>> result = new ArrayList<>();
        if (conn == null) return result;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT nombre, url, plataforma FROM sitios_dinamicos ORDER BY created_at")) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("nombre",     rs.getString(1));
                row.put("url",        rs.getString(2));
                row.put("plataforma", rs.getString(3));
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando sitios: {}", e.getMessage());
        }
        return result;
    }

    // ─── Categoria Stats ─────────────────────────────────────────────────────────

    public void guardarCategoriaStats(com.fasterxml.jackson.databind.JsonNode statsNode) {
        if (conn == null || statsNode == null) return;
        String now = LocalDateTime.now().format(DT);
        try {
            var it = statsNode.fields();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO categoria_stats (categoria, payload, updated_at) VALUES (?,?,?) " +
                    "ON CONFLICT(categoria) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at")) {
                while (it.hasNext()) {
                    var entry = it.next();
                    ps.setString(1, entry.getKey());
                    ps.setString(2, entry.getValue().toString());
                    ps.setString(3, now);
                    ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando categoria_stats: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public java.util.Map<String, String> cargarCategoriaStats() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (conn == null) return result;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT categoria, payload FROM categoria_stats ORDER BY categoria")) {
            while (rs.next()) result.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria_stats: {}", e.getMessage());
        }
        return result;
    }

    // ─── Precios externos (MercadoLibre, etc.) ───────────────────────────────────

    public void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        if (conn == null || resultados == null || resultados.isEmpty()) return;
        String hoy = LocalDate.now().format(DATE);
        try {
            // Borrar los del día para no duplicar
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM precios_externos WHERE producto_url=? AND sitio=? AND fecha=?")) {
                del.setString(1, productoUrl); del.setString(2, sitio); del.setString(3, hoy);
                del.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO precios_externos (producto_url,sitio,titulo,precio,externo_url,condicion,fecha) VALUES(?,?,?,?,?,?,?)")) {
                for (var r : resultados) {
                    ps.setString(1, productoUrl);
                    ps.setString(2, sitio);
                    ps.setString(3, (String) r.getOrDefault("titulo", ""));
                    ps.setDouble(4, ((Number) r.getOrDefault("precio", 0.0)).doubleValue());
                    ps.setString(5, (String) r.getOrDefault("url", ""));
                    ps.setString(6, (String) r.getOrDefault("condicion", "new"));
                    ps.setString(7, hoy);
                    ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando precios_externos: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
        var result = new java.util.ArrayList<java.util.Map<String,Object>>();
        if (conn == null) return result;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sitio,titulo,precio,externo_url,condicion,fecha " +
                "FROM precios_externos WHERE producto_url=? ORDER BY fecha DESC, precio ASC LIMIT 20")) {
            ps.setString(1, productoUrl);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                var row = new java.util.LinkedHashMap<String,Object>();
                row.put("sitio",     rs.getString("sitio"));
                row.put("titulo",    rs.getString("titulo"));
                row.put("precio",    rs.getDouble("precio"));
                row.put("url",       rs.getString("externo_url"));
                row.put("condicion", rs.getString("condicion"));
                row.put("fecha",     rs.getString("fecha"));
                result.add(row);
            }
        } catch (Exception e) { LOG.warn("[DB] Error cargando precios_externos: {}", e.getMessage()); }
        return result;
    }

    /** Actualiza la categoría de un producto (corrección por modelo ML) */
    public void actualizarCategoria(String url, String nuevaCategoria) {
        if (conn == null || url == null || nuevaCategoria == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE productos SET categoria=? WHERE url=?")) {
            ps.setString(1, nuevaCategoria);
            ps.setString(2, url);
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando categoria: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }


    // ─── Favoritos ───────────────────────────────────────────────────────────

    public void guardarFavorito(String url, String sitio, String nombre) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO favoritos (url, sitio, nombre, added_at, last_checked_at)
                VALUES (?, ?, ?, ?, NULL)
                ON CONFLICT(url) DO UPDATE SET sitio=excluded.sitio, nombre=excluded.nombre
                """)) {
            ps.setString(1, url);
            ps.setString(2, sitio);
            ps.setString(3, nombre);
            ps.setString(4, LocalDateTime.now().format(DT));
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando favorito: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public void eliminarFavorito(String url) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM favoritos WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando favorito: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public List<Map<String, String>> listarFavoritos() {
        List<Map<String, String>> result = new ArrayList<>();
        if (conn == null) return result;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT url, sitio, nombre, added_at, last_checked_at " +
                "FROM favoritos ORDER BY added_at DESC")) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("url",            rs.getString(1));
                row.put("sitio",          rs.getString(2));
                row.put("nombre",         rs.getString(3));
                row.put("added_at",       rs.getString(4));
                row.put("last_checked_at", rs.getString(5));
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando favoritos: {}", e.getMessage());
        }
        return result;
    }

    // ─── Outfit feedback ─────────────────────────────────────────────────────

    public void guardarOutfitFeedback(String genero, boolean liked, String torsoUrl,
                                       String piernasUrl, String calzadoUrl, String accesorioUrl) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO outfit_feedback
                    (genero, liked, torso_url, piernas_url, calzado_url, accesorio_url, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, genero);
            ps.setInt(2, liked ? 1 : 0);
            ps.setString(3, torsoUrl);
            ps.setString(4, piernasUrl);
            ps.setString(5, calzadoUrl);
            ps.setString(6, accesorioUrl);
            ps.setString(7, LocalDateTime.now().format(DT));
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit feedback: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    /** Fila cruda de feedback de outfits — el join con el catálogo vivo lo hace el caller. */
    public record OutfitFeedbackRow(String genero, boolean liked, String torsoUrl,
                                     String piernasUrl, String calzadoUrl, String accesorioUrl) {}

    /**
     * Lee todas las filas de outfit_feedback. Sin filtro por genero (scope global,
     * ver spec "Feedback-Driven Sampling" — el genero se ignora al construir las keys).
     * El caller (ApiController.buildFeedbackModel) hace el join url→Product contra el
     * catálogo vivo, ya que esta clase no conoce el AggregatedResult en memoria.
     */
    public List<OutfitFeedbackRow> obtenerOutfitFeedback() {
        List<OutfitFeedbackRow> result = new ArrayList<>();
        if (conn == null) return result;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT genero, liked, torso_url, piernas_url, calzado_url, accesorio_url " +
                "FROM outfit_feedback")) {
            while (rs.next()) {
                result.add(new OutfitFeedbackRow(
                        rs.getString("genero"),
                        rs.getInt("liked") == 1,
                        rs.getString("torso_url"),
                        rs.getString("piernas_url"),
                        rs.getString("calzado_url"),
                        rs.getString("accesorio_url")));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando outfit feedback: {}", e.getMessage());
        }
        return result;
    }

    public void marcarDescontinuado(String url) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE productos SET activo=0 WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error marcando descontinuado: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public void tocarFavorito(String url) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE favoritos SET last_checked_at=? WHERE url=?")) {
            ps.setString(1, LocalDateTime.now().format(DT));
            ps.setString(2, url);
            ps.executeUpdate();
            conn.commit();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_checked_at: {}", e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
        }
    }

    public boolean esProductoActivo(String url) {
        if (conn == null) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT activo FROM productos WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error consultando activo: {}", e.getMessage());
            return false;
        }
    }

    public record HistorialEntry(String fecha, double precio) {}

    public List<HistorialEntry> getHistorialPrecios(String url) {
        var result = new java.util.ArrayList<HistorialEntry>();
        if (conn == null || url == null) return result;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha")) {
            ps.setString(1, url);
            var rs = ps.executeQuery();
            while (rs.next())
                result.add(new HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
        } catch (Exception e) {
            LOG.warn("[DB] historial {}: {}", url, e.getMessage());
        }
        return result;
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: carga el historial de
     * múltiples URLs en una sola consulta {@code WHERE url IN (...)}, evitando el
     * patrón N+1 que resultaría de llamar la versión single-URL por producto
     * (usado por {@code SenalEnricher} para precomputar señal de compra sobre todo
     * el catálogo en un solo round-trip a la DB).
     *
     * @param urls URLs de productos a consultar; URLs vacías/blank son ignoradas
     * @return mapa url -&gt; historial (orden ascendente por fecha); URLs sin
     *         historial no aparecen como key
     */
    public Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls) {
        Map<String, List<HistorialEntry>> result = new HashMap<>();
        if (conn == null || urls == null || urls.isEmpty()) return result;

        List<String> validUrls = urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();
        if (validUrls.isEmpty()) return result;

        String placeholders = String.join(",", validUrls.stream().map(u -> "?").toList());
        String sql = "SELECT url, fecha, precio FROM precio_historico WHERE url IN (" +
                placeholders + ") ORDER BY url, fecha";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < validUrls.size(); i++) {
                ps.setString(i + 1, validUrls.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String url = rs.getString("url");
                    result.computeIfAbsent(url, k -> new ArrayList<>())
                          .add(new HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] historial batch ({} urls): {}", validUrls.size(), e.getMessage());
        }
        return result;
    }

    // ─── Clear methods ───────────────────────────────────────────────────────

    public void limpiarProductos() throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("DELETE FROM productos");
            st.execute("DELETE FROM precio_historico");
            st.execute("DELETE FROM categoria_stats");
            conn.commit();
            LOG.info("[DB] Catálogo, historial y stats de categorías eliminados.");
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    public void limpiarMlOutput() throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("DELETE FROM ml_output");
            conn.commit();
            LOG.info("[DB] Datos ML eliminados.");
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {}
}
