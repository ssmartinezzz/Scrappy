package ar.scraper.db;

import ar.scraper.model.PersistenciaException;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.sql.SQLException;

/**
 * Translates {@link SQLException} and Spring's {@link DataAccessException} into the domain's
 * {@link PersistenciaException} at a port boundary.
 */
final class Sql {

    @FunctionalInterface
    interface Consulta<T> {
        T ejecutar() throws SQLException;
    }

    @FunctionalInterface
    interface Accion {
        void ejecutar() throws SQLException;
    }

    private Sql() {}

    static <T> T traducir(Consulta<T> consulta) {
        try {
            return consulta.ejecutar();
        } catch (SQLException | DataAccessException e) {
            throw new PersistenciaException(e);
        }
    }

    static void traducir(Accion accion) {
        try {
            accion.ejecutar();
        } catch (SQLException | DataAccessException e) {
            throw new PersistenciaException(e);
        }
    }

    /**
     * A method that swallows its exception to return a sentinel would otherwise COMMIT the
     * half-done unit. Throws when there is no transaction: that means the object was built with
     * {@code new} and {@code @Transactional} never ran.
     */
    static void marcarRollback() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }
}
