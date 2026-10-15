package ar.scraper.db;

import ar.scraper.notificaciones.PreferenciaAvisos;
import ar.scraper.notificaciones.PreferenciaAvisosPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PreferenciaAvisosRepository implements PreferenciaAvisosPort {

    private final JdbcTemplate jdbc;

    public PreferenciaAvisosRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<PreferenciaAvisos> leer(UUID usuarioId) {
        return jdbc.query("""
                SELECT notificar_bajas_precio, email IS NOT NULL FROM usuario WHERE id = ?
                """, (rs, i) -> new PreferenciaAvisos(rs.getBoolean(1), rs.getBoolean(2)), usuarioId)
                .stream().findFirst();
    }

    @Override
    public boolean actualizar(UUID usuarioId, boolean notificarBajasPrecio) {
        return jdbc.update("UPDATE usuario SET notificar_bajas_precio = ? WHERE id = ? AND email IS NOT NULL",
                notificarBajasPrecio, usuarioId) == 1;
    }
}
