package ar.scraper.indices;

import java.util.function.UnaryOperator;

/**
 * The index a product is deflated with: its category's own index when the category has one, else its rubro's,
 * else the CPI. The two lookups return an {@code indice.codigo} or {@code null}; they are the taxonomy tables
 * seen from here, so this package does not depend on where they are read.
 */
public final class DeflactorPorRubro {

    private final UnaryOperator<String> indiceDeCategoria;
    private final UnaryOperator<String> indiceDeRubro;

    public DeflactorPorRubro(UnaryOperator<String> indiceDeCategoria, UnaryOperator<String> indiceDeRubro) {
        this.indiceDeCategoria = indiceDeCategoria;
        this.indiceDeRubro = indiceDeRubro;
    }

    public Indice resolver(String rubro, String categoria) {
        String codigo = indiceDeCategoria.apply(categoria);
        if (codigo == null) codigo = indiceDeRubro.apply(rubro);
        return codigo == null ? Indice.IPC : Indice.valueOf(codigo);
    }
}
