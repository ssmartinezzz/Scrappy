package ar.scraper.classification;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Immutable snapshot of the rubro taxonomy: which rubros exist, which rubro each (categoria, sub_categoria)
 * belongs to, and which price index deflates them. An empty taxonomy is rejected on construction: it would
 * reclassify every product as the fallback rubro without a single error.
 */
public final class RubroCatalogo {

    /** {@code indice} is an {@code indice.codigo}; the weights decide who wins between a forced site and a category. */
    public record Rubro(String clave, String etiqueta, int orden, String indice, int pesoForzado, int pesoCategoria) {}

    /** {@code subCategoria} is {@code ""} for the category as a whole. */
    public record CategoriaRubro(String categoria, String subCategoria, String rubro, boolean pisaForzado) {}

    private record Clave(String categoria, String subCategoria) {}

    private final Map<String, Rubro> rubros;
    private final List<Rubro> orden;
    private final Map<Clave, CategoriaRubro> filas;
    private final Map<String, String> indicePorCategoria;

    public RubroCatalogo(Collection<Rubro> rubros, Collection<CategoriaRubro> filas, Map<String, String> indicePorCategoria) {
        Map<String, Rubro> porClave = new LinkedHashMap<>();
        rubros.stream().sorted(Comparator.comparingInt(Rubro::orden))
                .forEach(r -> porClave.put(r.clave(), r));
        Map<Clave, CategoriaRubro> porCategoria = new LinkedHashMap<>();
        filas.forEach(f -> porCategoria.put(new Clave(f.categoria(), f.subCategoria()), f));
        if (porClave.isEmpty() || porCategoria.isEmpty()) {
            throw new IllegalStateException("The rubro taxonomy is empty: " + porClave.size() + " rubros, "
                    + porCategoria.size() + " category rows");
        }
        this.rubros = Map.copyOf(porClave);
        this.orden = List.copyOf(porClave.values());
        this.filas = Map.copyOf(porCategoria);
        this.indicePorCategoria = Map.copyOf(indicePorCategoria);
    }

    public List<Rubro> rubros() {
        return orden;
    }

    public Collection<CategoriaRubro> filas() {
        return filas.values();
    }

    public Map<String, String> indicesPorCategoria() {
        return indicePorCategoria;
    }

    public Optional<Rubro> rubro(String clave) {
        return clave == null ? Optional.empty() : Optional.ofNullable(rubros.get(clave));
    }

    /** The row of this exact (categoria, sub); a null or blank sub means the category as a whole. */
    public Optional<CategoriaRubro> fila(String categoria, String subCategoria) {
        if (categoria == null) return Optional.empty();
        return Optional.ofNullable(filas.get(new Clave(categoria, StringUtils.defaultString(subCategoria))));
    }

    public Optional<CategoriaRubro> fila(String categoria) {
        return fila(categoria, "");
    }

    /** The {@code indice.codigo} of this rubro, or {@code null} when the rubro is unknown. */
    public String indiceDeRubro(String clave) {
        return rubro(clave).map(Rubro::indice).orElse(null);
    }

    /** The {@code indice.codigo} that overrides the rubro's for this category, or {@code null}. */
    public String indiceDeCategoria(String categoria) {
        return categoria == null ? null : indicePorCategoria.get(categoria);
    }
}
