package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistence for taste signal: {@code outfit_feedback_item} (per-item likes and
 * dislikes, scoped by estilo) and {@code categoria_dismiss} (feed-wide "not
 * interested").
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). The
 * {@code OutfitItemRow} record stays nested on DatabaseService — callers and
 * tests name it {@code DatabaseService.OutfitItemRow}.</p>
 */
class FeedbackRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbackRepository.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    FeedbackRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Persiste un único veredicto (slot, url, liked, estilo) en outfit_feedback_item —
     * una fila por item calificado (ADR-1 de outfit-per-item-feedback). El estilo
     * ("gym" | "casual" | "catalog") separa la señal de gusto por superficie: el
     * builder gym y casual leen buckets distintos, el feed usa "catalog" (ver
     * FeedbackModels.build).
     */
    void guardarOutfitFeedbackItem(String genero, String slot, String url, boolean liked, String estilo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO outfit_feedback_item
                        (genero, slot, url, liked, estilo, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
            ps.setString(1, genero);
            ps.setString(2, slot);
            ps.setString(3, url);
            ps.setBoolean(4, liked);
            ps.setString(5, (estilo == null || estilo.isBlank()) ? "gym" : estilo);
            ps.setString(6, LocalDateTime.now().format(DT));
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit feedback item: {}", e.getMessage());
        }
    }

    /**
     * Lee todas las filas de outfit_feedback_item (con su estilo). Sin filtro por
     * genero ni estilo — el filtrado por estilo lo hace el caller
     * (FeedbackModels.build) según la superficie. El caller también hace
     * el join url→Product contra el catálogo vivo, ya que esta clase no conoce el
     * AggregatedResult en memoria.
     */
    List<DatabaseService.OutfitItemRow> obtenerOutfitFeedback() {
        List<DatabaseService.OutfitItemRow> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT slot, url, liked, estilo FROM outfit_feedback_item")) {
            while (rs.next()) {
                String estilo = rs.getString("estilo");
                result.add(new DatabaseService.OutfitItemRow(
                        rs.getString("slot"),
                        rs.getString("url"),
                        rs.getBoolean("liked"),
                        (estilo == null || estilo.isBlank()) ? "gym" : estilo));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando outfit feedback item: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Borra TODO el historial de feedback (todos los estilos + tabla legacy).
     * Backward-compat: el reset scoped por estilo usa {@link #limpiarOutfitFeedback(String)}.
     */
    void limpiarOutfitFeedback() {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM outfit_feedback_item");
                st.executeUpdate("DELETE FROM outfit_feedback");
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error limpiando outfit feedback: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error limpiando outfit feedback: {}", e.getMessage());
        }
    }

    /**
     * Borra el historial de feedback de UN estilo ("gym" | "casual"). No toca las
     * filas de otros estilos ni las del feed ("catalog") — el reset de gustos de
     * cada superficie del builder es independiente. estilo null/blank → no-op
     * (evita borrar todo por accidente; para eso está el overload sin argumentos).
     */
    void limpiarOutfitFeedback(String estilo) {
        if (estilo == null || estilo.isBlank()) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM outfit_feedback_item WHERE estilo=?")) {
            ps.setString(1, estilo);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error limpiando outfit feedback (estilo={}): {}", estilo, e.getMessage());
        }
    }

    /**
     * Marca una categoria como "no me interesa" feed-wide (Decision 1 de
     * design.md, personalized-recommendations-feed). Idempotente: si la
     * categoria ya está dismissed, no inserta una fila duplicada.
     */
    void guardarCategoriaDismiss(String categoria) {
        if (categoria == null || categoria.isBlank()) return;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement check = c.prepareStatement(
                        "SELECT 1 FROM categoria_dismiss WHERE categoria=?")) {
                    check.setString(1, categoria);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            c.rollback(); // ya existe — no-op idempotente
                            return;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO categoria_dismiss (categoria, created_at)
                        VALUES (?, ?)
                        """)) {
                    ps.setString(1, categoria);
                    ps.setString(2, LocalDateTime.now().format(DT));
                    ps.executeUpdate();
                    c.commit();
                }
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando categoria dismiss: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando categoria dismiss: {}", e.getMessage());
        }
    }

    /** Revierte el dismiss de una categoria (undo). Safe no-op si no existía. */
    void borrarCategoriaDismiss(String categoria) {
        if (categoria == null || categoria.isBlank()) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM categoria_dismiss WHERE categoria=?")) {
            ps.setString(1, categoria);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error borrando categoria dismiss: {}", e.getMessage());
        }
    }

    /** Lee todas las categorias dismissed feed-wide. */
    Set<String> obtenerCategoriaDismiss() {
        Set<String> result = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT categoria FROM categoria_dismiss")) {
            while (rs.next()) {
                result.add(rs.getString("categoria"));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria dismiss: {}", e.getMessage());
        }
        return result;
    }
}
