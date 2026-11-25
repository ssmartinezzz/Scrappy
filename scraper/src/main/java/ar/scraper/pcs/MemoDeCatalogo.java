package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What a PC build derives from the catalog alone, kept per catalog list instance: the products of each category, the
 * pools without combos and the parsed {@link TechSpecs} of every product in them. The snapshot list is replaced on
 * load and never mutated, so its identity keys the memo; a list that is a different instance (or has another size) gets
 * a new one. The single holder keeps the list only weakly and is dropped when the catalog changes, so an old snapshot
 * is not kept alive beyond what the memo itself references (the products of the PC categories that were asked for).
 */
final class MemoDeCatalogo {

    private static volatile MemoDeCatalogo actual;

    private final WeakReference<List<Product>> origen;
    private final int tamanio;
    private final Map<String, Categoria> categorias = new ConcurrentHashMap<>();

    private MemoDeCatalogo(List<Product> catalogo) {
        this.origen = new WeakReference<>(catalogo);
        this.tamanio = catalogo.size();
    }

    static MemoDeCatalogo de(List<Product> catalogo) {
        MemoDeCatalogo m = actual;
        if (m != null && m.origen.get() == catalogo && m.tamanio == catalogo.size()) return m;
        m = new MemoDeCatalogo(catalogo);
        actual = m;
        return m;
    }

    static void soltar() {
        actual = null;
    }

    /** The category's products with the soft filters of a build applied: excluded urls, then combos. */
    List<Product> pool(List<Product> catalogo, String categoria, Set<String> excluir) {
        Categoria c = categoria(catalogo, categoria);
        if (excluir.isEmpty()) return c.sinExclusion;
        List<Product> frescos = c.todos.stream().filter(p -> !excluir.contains(p.url())).toList();
        if (frescos.isEmpty()) return c.sinExclusion;
        List<Product> frescosSinCombos = c.sinCombos.stream().filter(p -> !excluir.contains(p.url())).toList();
        return frescosSinCombos.isEmpty() ? frescos : frescosSinCombos;
    }

    /** The category's products as the catalog lists them, before any soft filter. */
    List<Product> todos(List<Product> catalogo, String categoria) {
        return categoria(catalogo, categoria).todos;
    }

    /** Parsed once per product of a requested category; any other product is parsed on the spot. */
    TechSpecs specsDe(Product producto) {
        Categoria c = producto.categoria() == null ? null : categorias.get(producto.categoria());
        TechSpecs memorizado = c == null ? null : c.specs.get(producto);
        return memorizado != null ? memorizado : TechSpecsParser.parse(producto.nombre(), producto.categoria());
    }

    private Categoria categoria(List<Product> catalogo, String nombre) {
        return categorias.computeIfAbsent(nombre, n -> new Categoria(catalogo, n));
    }

    private static final class Categoria {
        final List<Product> todos;
        final List<Product> sinCombos;
        final List<Product> sinExclusion;
        /** Written only here, before publication, and read-only afterwards. */
        final Map<Product, TechSpecs> specs = new IdentityHashMap<>();

        Categoria(List<Product> catalogo, String nombre) {
            todos = catalogo.stream().filter(p -> nombre.equals(p.categoria())).collect(Collectors.toList());
            sinCombos = todos.stream().filter(p -> !PcBuilder.esCombo(p.nombre())).collect(Collectors.toList());
            sinExclusion = sinCombos.isEmpty() ? todos : sinCombos;
            for (Product p : todos) specs.put(p, TechSpecsParser.parse(p.nombre(), p.categoria()));
        }
    }
}
