package ar.scraper.db;

import ar.scraper.model.PersistenciaException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class SqlTest {

    @Test
    void consultaTraduceSqlException() {
        SQLException causa = new SQLException("boom");
        PersistenciaException e = assertThrows(PersistenciaException.class,
                () -> Sql.traducir((Sql.Consulta<String>) () -> { throw causa; }));
        assertSame(causa, e.getCause());
    }

    @Test
    void consultaTraduceDataAccessException() {
        DataAccessResourceFailureException causa = new DataAccessResourceFailureException("pool down");
        PersistenciaException e = assertThrows(PersistenciaException.class,
                () -> Sql.traducir((Sql.Consulta<String>) () -> { throw causa; }));
        assertSame(causa, e.getCause());
    }

    @Test
    void accionTraduceDataAccessException() {
        DataAccessResourceFailureException causa = new DataAccessResourceFailureException("pool down");
        PersistenciaException e = assertThrows(PersistenciaException.class,
                () -> Sql.traducir((Sql.Accion) () -> { throw causa; }));
        assertSame(causa, e.getCause());
    }

    @Test
    void consultaDevuelveElValor() {
        assertEquals("ok", Sql.traducir((Sql.Consulta<String>) () -> "ok"));
    }
}
