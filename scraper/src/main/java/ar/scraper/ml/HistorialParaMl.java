package ar.scraper.ml;

import java.nio.file.Path;

/** El historial de precios que lee el pipeline ML: derivado de la base, efímero, nunca estado propio. */
public interface HistorialParaMl {

    /** Deja en {@code hist} el historial de los productos de {@code productosJson}. */
    void preparar(Path hist, String productosJson);

    /** Borra {@code hist}: lo que quede en disco después de una corrida no es fuente de verdad. */
    void descartar(Path hist);

    HistorialParaMl NINGUNO = new HistorialParaMl() {
        @Override public void preparar(Path hist, String productosJson) {}
        @Override public void descartar(Path hist) {}
    };
}
