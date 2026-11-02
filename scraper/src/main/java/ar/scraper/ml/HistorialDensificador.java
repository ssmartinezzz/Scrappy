package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code precio_historico} guarda cambios; el pipeline ML espera un punto por día (media móvil de 7,
 * {@code histN >= 3}). Esto expande el log a un escalón diario: el último cambio vigente se arrastra
 * día a día, desde su fecha (o el inicio de la ventana) hasta {@code hoy}.
 */
final class HistorialDensificador {

    private HistorialDensificador() {}

    /** {@code cambios} ordenados por fecha ascendente (ISO); a lo sumo {@code dias} puntos. */
    static List<HistorialEntry> densificar(List<HistorialEntry> cambios, LocalDate hoy, int dias) {
        var out = new ArrayList<HistorialEntry>(dias);
        int i = 0;
        HistorialEntry vigente = null;
        for (LocalDate dia = hoy.minusDays(dias - 1L); !dia.isAfter(hoy); dia = dia.plusDays(1)) {
            String iso = dia.toString();
            while (i < cambios.size() && cambios.get(i).fecha().compareTo(iso) <= 0) {
                vigente = cambios.get(i++);
            }
            if (vigente != null) out.add(new HistorialEntry(iso, vigente.precio()));
        }
        return out;
    }
}
