package ar.scraper.ml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** El historial de precios que lee el pipeline ML: derivado de la base, efímero, nunca estado propio. */
public interface HistorialParaMl {

    /** Deja en {@code hist} el historial de los productos de {@code productosJson}. */
    void preparar(Path hist, String productosJson);

    /**
     * Igual, leyendo los productos del archivo que el pipeline va a leer. Esta versión lo carga entero;
     * quien pueda recorrerlo sin cargarlo la redefine.
     */
    default void preparar(Path hist, Path productos) {
        try {
            preparar(hist, Files.readString(productos));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Borra {@code hist}: lo que quede en disco después de una corrida no es fuente de verdad. */
    void descartar(Path hist);

    HistorialParaMl NINGUNO = new HistorialParaMl() {
        @Override public void preparar(Path hist, String productosJson) {}
        @Override public void descartar(Path hist) {}
    };
}
