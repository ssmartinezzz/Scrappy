package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agrupa productos del mismo artículo provenientes de distintos sitios.
 *
 * Criterio de identidad: categoría + marca + modelo normalizado
 * (sin color, talle, género ni descriptores variables).
 *
 * Ejemplo:
 *   "Nike Air Force 1 Blanco Talle 42" (VCP)  ─┐
 *   "Nike Air Force 1 Negro"            (Freres)├─ mismo grupo
 *   "Nike Air Force 1"                 (Sporting)┘
 */
@Component
public class GroupingService {

    // Palabras a ignorar en el cálculo de identidad
    private static final Set<String> STOP = Set.of(
        "negro","negra","blanco","blanca","azul","rojo","roja","verde","gris",
        "beige","naranja","amarillo","violeta","marron","celeste","rosa",
        "plateado","dorado","tostado","crudo","navy","khaki","oliva","militar",
        "ivory","offwhite","off","white","black","grey","gray","red","blue",
        "hombre","mujer","masculino","femenino","unisex","dama","caballero",
        "xs","xxs","s","m","l","xl","xxl","xxxl","unico","talle","talla","size",
        "nuevo","nueva","original","importado","coleccion","edicion","temporada",
        "de","la","el","los","las","con","para","en","y","a","e"
    );

    /**
     * Agrupa una lista de productos en grupos de artículo comparable.
     *
     * @param productos lista ya normalizada y enriquecida con ML
     * @param soloMultiSitio si true, solo retorna grupos con 2+ sitios distintos
     */
    // Umbral mínimo de similitud Jaccard para considerar 2 productos como "el mismo artículo"
    private static final double JACCARD_THRESHOLD = 0.55;

    public List<ProductGroup> agrupar(List<Product> productos, boolean soloMultiSitio) {
        // Paso 1: agrupar por clave de identidad aproximada (prefijo eficiente)
        Map<String, List<Product>> preGrupos = new LinkedHashMap<>();
        for (Product p : productos) {
            String key = calcularIdentidad(p);
            preGrupos.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        // Paso 2: dentro de cada pregrupo, verificar similitud Jaccard real
        // Evita que productos con mismo prefijo pero diferente modelo se agrupen
        List<List<Product>> gruposFinales = new ArrayList<>();
        for (List<Product> preGrupo : preGrupos.values()) {
            if (preGrupo.size() == 1) {
                gruposFinales.add(preGrupo);
                continue;
            }
            // Sub-agrupar por Jaccard dentro del pregrupo
            gruposFinales.addAll(subAgruparPorJaccard(preGrupo));
        }

        return gruposFinales.stream()
                .filter(g -> !g.isEmpty())
                .map(ProductGroup::new)
                .filter(g -> !soloMultiSitio || g.sitiosDistintos() >= 2)
                .sorted(Comparator.comparingDouble(ProductGroup::precioMinimo))
                .collect(Collectors.toList());
    }

    /**
     * Sub-agrupa una lista de productos por similitud Jaccard sobre bag-of-words.
     * Evita el falso positivo de "Nike Air Force" vs "Nike Air Max":
     *   words("Nike Air Force") = {nike, air, force}
     *   words("Nike Air Max")   = {nike, air, max}
     *   Jaccard = |{nike,air}| / |{nike,air,force,max}| = 2/4 = 0.5 → umbral no superado ✗
     *
     *   words("Nike Air Force 1 Blanco") = {nike, air, force}
     *   words("Nike Air Force 1 Negro")  = {nike, air, force}
     *   Jaccard = 3/3 = 1.0 ✓
     */
    private List<List<Product>> subAgruparPorJaccard(List<Product> productos) {
        List<List<Product>> grupos = new ArrayList<>();
        boolean[] asignado = new boolean[productos.size()];

        for (int i = 0; i < productos.size(); i++) {
            if (asignado[i]) continue;
            List<Product> grupo = new ArrayList<>();
            grupo.add(productos.get(i));
            asignado[i] = true;
            Set<String> wordsI = palabrasSignificativas(productos.get(i));

            for (int j = i + 1; j < productos.size(); j++) {
                if (asignado[j]) continue;
                // Solo agrupar si son de sitios distintos
                if (productos.get(i).sitio().equals(productos.get(j).sitio())) continue;
                Set<String> wordsJ = palabrasSignificativas(productos.get(j));
                if (jaccardSimilarity(wordsI, wordsJ) >= JACCARD_THRESHOLD) {
                    grupo.add(productos.get(j));
                    asignado[j] = true;
                }
            }
            grupos.add(grupo);
        }
        return grupos;
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> union        = new HashSet<>(a); union.addAll(b);
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    private Set<String> palabrasSignificativas(Product p) {
        String texto = ((p.marca() != null ? p.marca() : "") + " "
                     + (p.nombre() != null ? p.nombre() : "")).toLowerCase();
        texto = texto.replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                     .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                     .replaceAll("[úùü]","u").replaceAll("[ñ]","n");
        return Arrays.stream(texto.split("[\\s\\-_/.,()]+"))
                .map(t -> t.replaceAll("[^a-z0-9]",""))
                .filter(t -> t.length() >= 3 && !STOP.contains(t))
                .filter(t -> !t.matches("^\\d{1,2}$"))
                .collect(Collectors.toSet());
    }

    /**
     * Identidad de un producto: string que captura marca + modelo
     * sin variables (color, talle, género).
     */
    String calcularIdentidad(Product p) {
        String marca = normalizar(p.marca() != null ? p.marca() : "");
        String nombre = normalizar(p.nombre() != null ? p.nombre() : "");
        String cat   = (p.categoria() != null ? p.categoria() : "").toLowerCase().trim();

        // Combinar marca + nombre, filtrar palabras stop
        String combined = (marca + " " + nombre).trim();
        String[] tokens = combined.split("[\\s\\-_/.,()]+");

        List<String> palabrasSignificativas = Arrays.stream(tokens)
                .map(String::toLowerCase)
                .map(t -> t.replaceAll("[^a-z0-9]", ""))
                .filter(t -> t.length() >= 3)
                .filter(t -> !STOP.contains(t))
                // Filtrar números puros que pueden ser talle (1-3 dígitos)
                .filter(t -> !t.matches("^\\d{1,2}$"))
                .limit(5)
                .collect(Collectors.toList());

        String key = String.join("_", palabrasSignificativas);
        return cat.replaceAll("[^a-z]", "") + "_" + key;
    }

    private String normalizar(String s) {
        if (s == null || s.isBlank()) return "";
        return s.toLowerCase()
                .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n")
                .trim();
    }

    // ─── ProductGroup ─────────────────────────────────────────────────────────

    public static class ProductGroup {
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
}
