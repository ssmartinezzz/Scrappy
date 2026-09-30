package ar.scraper.db;

import ar.scraper.model.PersistenciaException;

import java.sql.SQLException;

/** Translates {@link SQLException} into the domain's {@link PersistenciaException} at a port boundary. */
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
        } catch (SQLException e) {
            throw new PersistenciaException(e);
        }
    }

    static void traducir(Accion accion) {
        try {
            accion.ejecutar();
        } catch (SQLException e) {
            throw new PersistenciaException(e);
        }
    }
}
