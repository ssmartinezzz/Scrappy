package ar.scraper.pcs;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * El pick de un slot contra la mejor de su gama y la mejor del catálogo, sobre la escala propia del slot.
 * {@code pick.puntaje} 0 es abstención (sin benchmark); una referencia {@code null} es "nadie califica".
 */
public record ReferenciaDeRendimiento(String escala, Puntaje pick, Puntaje mejorDeGama, Puntaje mejorDelCatalogo) {

    public record Puntaje(String modelo, int puntaje, Gama gama) {}

    /**
     * Una pasada sobre {@code catalogo}: los specs del pool de la categoría antes del {@code excluir}
     * suave (el mercado, no los candidatos del slot). Empata el primero del pool.
     */
    static ReferenciaDeRendimiento de(String escala, TechSpecs elegido, List<TechSpecs> catalogo,
            ToIntFunction<TechSpecs> puntaje) {
        Puntaje deGama = null;
        Puntaje delCatalogo = null;
        for (TechSpecs s : catalogo) {
            int p = puntaje.applyAsInt(s);
            if (p <= 0) continue;
            if (delCatalogo == null || p > delCatalogo.puntaje()) delCatalogo = new Puntaje(s.modelo(), p, s.gama());
            if (s.gama() == elegido.gama() && elegido.gama() != Gama.DESCONOCIDA
                    && (deGama == null || p > deGama.puntaje())) deGama = new Puntaje(s.modelo(), p, s.gama());
        }
        return new ReferenciaDeRendimiento(escala,
                new Puntaje(elegido.modelo(), puntaje.applyAsInt(elegido), elegido.gama()), deGama, delCatalogo);
    }
}
