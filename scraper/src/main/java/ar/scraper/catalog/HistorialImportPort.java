package ar.scraper.catalog;

import java.util.List;
import java.util.Map;

/** Carga única del historial que antes guardaba el ML; el write path normal vive en el upsert. */
public interface HistorialImportPort {

    /**
     * Inserta los cambios de precio de los productos que existen, en una sola transacción y sin
     * pisar filas ya presentes ({@code (url, fecha)} es único). Devuelve las filas insertadas.
     */
    int importarCambios(Map<String, List<HistorialEntry>> cambiosPorUrl);
}
