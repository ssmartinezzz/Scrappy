package ar.scraper.ml;

import java.io.IOException;
import java.io.OutputStream;

/**
 * What the ML pipeline reads as {@code ml_productos.json}, produced straight onto the file instead of
 * as a string: the catalogue is the biggest thing alive when this runs.
 */
@FunctionalInterface
public interface ProductosParaMl {

    /** Writes the JSON array; does not close {@code out}. */
    void escribirEn(OutputStream out) throws IOException;
}
