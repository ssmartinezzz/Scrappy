package ar.scraper.db;

import ar.scraper.feedback.OutfitItemRow;

import ar.scraper.feedback.FeedbackPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * {@code outfit_feedback_item} (per-item likes and dislikes, scoped by estilo) and
 * {@code categoria_dismiss} (feed-wide "not interested").
 */
@Repository
class FeedbackRepository implements FeedbackPort {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbackRepository.class);

    private final DataSource dataSource;

    FeedbackRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void guardarOutfitFeedbackItem(UUID usuarioId, String genero, String slot, String url,
                                   boolean liked, String estilo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO outfit_feedback_item
                        (usuario_id, genero, slot, url, liked, estilo, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
            ps.setObject(1, usuarioId);
            ps.setString(2, genero);
            ps.setString(3, slot);
            ps.setString(4, url);
            ps.setBoolean(5, liked);
            ps.setString(6, StringUtils.isBlank(estilo) ? "gym" : estilo);
            ps.setObject(7, Timestamps.now());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit feedback item: {}", e.getMessage());
        }
    }

    /**
     * Sin filtro por genero ni estilo — el filtrado por estilo lo hace el caller
     * (FeedbackModels.build) según la superficie.
     */
    @Override
    public List<OutfitItemRow> obtenerOutfitFeedback(UUID usuarioId) {
        List<OutfitItemRow> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT slot, url, liked, estilo FROM outfit_feedback_item WHERE usuario_id=?")) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String estilo = rs.getString("estilo");
                result.add(new OutfitItemRow(
                        rs.getString("slot"),
                        rs.getString("url"),
                        rs.getBoolean("liked"),
                        (StringUtils.isBlank(estilo)) ? "gym" : estilo));
            }
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando outfit feedback item: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void limpiarOutfitFeedback(UUID usuarioId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM outfit_feedback_item WHERE usuario_id=?")) {
            ps.setObject(1, usuarioId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error limpiando outfit feedback: {}", e.getMessage());
        }
    }

    /**
     * No toca las filas de otros estilos ni las del feed ("catalog") — el reset de gustos de cada
     * superficie del builder es independiente. estilo null/blank → no-op (evita borrar todo por
     * accidente; para eso está el overload sin argumentos).
     */
    @Override
    public void limpiarOutfitFeedback(UUID usuarioId, String estilo) {
        if (StringUtils.isBlank(estilo)) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM outfit_feedback_item WHERE usuario_id=? AND estilo=?")) {
            ps.setObject(1, usuarioId);
            ps.setString(2, estilo);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error limpiando outfit feedback (estilo={}): {}", estilo, e.getMessage());
        }
    }

    /** Idempotente: si la categoria ya está dismissed, no inserta una fila duplicada. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void guardarCategoriaDismiss(UUID usuarioId, String categoria) {
        if (StringUtils.isBlank(categoria)) return;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM categoria_dismiss WHERE usuario_id=? AND categoria=?")) {
                check.setObject(1, usuarioId);
                check.setString(2, categoria);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) return;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO categoria_dismiss (usuario_id, categoria, created_at)
                    VALUES (?, ?, ?)
                    """)) {
                ps.setObject(1, usuarioId);
                ps.setString(2, categoria);
                ps.setObject(3, Timestamps.now());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando categoria dismiss: {}", e.getMessage());
            Sql.marcarRollback();
        }
    }

    /** Safe no-op si no existía. */
    @Override
    public void borrarCategoriaDismiss(UUID usuarioId, String categoria) {
        if (StringUtils.isBlank(categoria)) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM categoria_dismiss WHERE usuario_id=? AND categoria=?")) {
            ps.setObject(1, usuarioId);
            ps.setString(2, categoria);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error borrando categoria dismiss: {}", e.getMessage());
        }
    }

    @Override
    public Set<String> obtenerCategoriaDismiss(UUID usuarioId) {
        Set<String> result = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT categoria FROM categoria_dismiss WHERE usuario_id=?")) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("categoria"));
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria dismiss: {}", e.getMessage());
        }
        return result;
    }
}
