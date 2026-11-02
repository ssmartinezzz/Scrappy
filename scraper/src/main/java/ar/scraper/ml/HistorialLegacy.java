package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** El viejo {@code precio_historico.json} guardaba un punto por día avistado; la tabla, sólo cambios. */
final class HistorialLegacy {

    private static final double EPSILON_PRECIO = 0.01;

    private HistorialLegacy() {}

    /** Último precio de cada día (como el upsert) y sólo los días en que el precio se movió. */
    static List<HistorialEntry> colapsarACambios(List<HistorialEntry> puntos) {
        var porFecha = new ArrayList<>(puntos);
        porFecha.sort(Comparator.comparing(HistorialEntry::fecha)); // estable: dentro del día manda el orden del archivo
        var cambios = new ArrayList<HistorialEntry>();
        for (int i = 0; i < porFecha.size(); i++) {
            HistorialEntry p = porFecha.get(i);
            boolean ultimoDelDia = i + 1 == porFecha.size()
                    || !porFecha.get(i + 1).fecha().equals(p.fecha());
            if (!ultimoDelDia) continue;
            boolean seMovio = cambios.isEmpty()
                    || Math.abs(cambios.getLast().precio() - p.precio()) > EPSILON_PRECIO;
            if (seMovio) cambios.add(p);
        }
        return cambios;
    }
}
