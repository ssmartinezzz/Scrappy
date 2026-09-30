package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public class ProductGroup {
    private final List<Product> productos;
    private final String nombre;
    private final String categoria;
    private final String img;

    public ProductGroup(List<Product> items) {
        this.productos = items.stream()
                .sorted(Comparator.comparingDouble(Product::precio))
                .collect(Collectors.toList());

        this.nombre = productos.stream()
                .min(Comparator.comparingInt(p -> p.nombre().length()))
                .map(Product::nombre).orElse("");

        this.categoria = productos.isEmpty() ? "" :
                (productos.get(0).categoria() != null ? productos.get(0).categoria() : "");

        this.img = productos.stream()
                .filter(p -> StringUtils.isNotBlank(p.imagenUrl()))
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

    public double ahorroPct() {
        if (precioMaximo() <= 0) return 0;
        return (precioMaximo() - precioMinimo()) / precioMaximo() * 100;
    }
}
