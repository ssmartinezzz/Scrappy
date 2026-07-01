package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Value object representing a group of comparable products across sites.
 *
 * <p>Promoted to top-level from its former home as a nested class inside
 * {@code GroupingService} (Work Unit 2 of the aggregator SOLID
 * modularization). Getter signatures are FROZEN — {@code ApiController}
 * JSON-maps them directly ({@code getNombre}, {@code getCategoria},
 * {@code getImg}, {@code getProductos}, {@code sitiosDistintos},
 * {@code precioMinimo}, {@code precioMaximo}, {@code ahorroPct}).</p>
 */
public class ProductGroup {
    private final List<Product> productos;
    private final String nombre;
    private final String categoria;
    private final String img;

    public ProductGroup(List<Product> items) {
        // Ordenar de más barato a más caro
        this.productos = items.stream()
                .sorted(Comparator.comparingDouble(Product::precio))
                .collect(Collectors.toList());

        // Nombre canónico: el más corto (menos descriptores extra)
        this.nombre = productos.stream()
                .min(Comparator.comparingInt(p -> p.nombre().length()))
                .map(Product::nombre).orElse("");

        // Categoría del primer producto
        this.categoria = productos.isEmpty() ? "" :
                (productos.get(0).categoria() != null ? productos.get(0).categoria() : "");

        // Imagen: preferir la del producto más barato con imagen disponible
        this.img = productos.stream()
                .filter(p -> p.imagenUrl() != null && !p.imagenUrl().isBlank())
                .findFirst()
                .map(Product::imagenUrl).orElse("");
    }

    public List<Product> getProductos() { return productos; }
    public String getNombre()           { return nombre; }
    public String getCategoria()        { return categoria; }
    public String getImg()              { return img; }
    public int    size()                { return productos.size(); }

    public int sitiosDistintos() {
        return (int) productos.stream()
                .map(Product::sitio).distinct().count();
    }

    public double precioMinimo() {
        return productos.isEmpty() ? 0 :
                productos.get(0).precio();
    }

    public double precioMaximo() {
        return productos.isEmpty() ? 0 :
                productos.get(productos.size()-1).precio();
    }

    /** % de ahorro entre el más caro y el más barato */
    public double ahorroPct() {
        if (precioMaximo() <= 0) return 0;
        return (precioMaximo() - precioMinimo()) / precioMaximo() * 100;
    }
}
