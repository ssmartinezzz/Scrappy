package ar.scraper.web;

import ar.scraper.model.Product;
import org.springframework.http.ResponseEntity;

/**
 * Brand browser and the curated "Mejores picks" per category.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 *
 * <p>{@code precioUnitario} is NOT moved here — it stays as a static delegate on
 * {@link ApiController} because a test calls it directly. The implementation
 * lives in {@link ProductJson}, which is what this class calls.</p>
 */
class MarcasPicksEndpoints {

    /** Máximo de productos mostrados por categoría en Mejores Picks. */
    private static final int MAX_PICKS_POR_CATEGORIA = 10;

    private final ScraperService service;

    MarcasPicksEndpoints(ScraperService service) {
        this.service = service;
    }

    private String safe(String s) { return ProductJson.safe(s); }

    // ─── Marcas browser ──────────────────────────────────────────────────────────

    ResponseEntity<Object> marcasBrowser(String rubro, String q, String sort) {

        var r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

        // Agrupar por marca — BrandExtractor ya abstiene a "" en vez de caer
        // al nombre del sitio (V19, design DD8), así que las tres capas que
        // vivían acá para filtrar ESE fallback (marca==sitio exacto, un set
        // de 18 sitios hardcodeado, un mínimo de 2 caracteres) son código
        // muerto: marca ya sólo puede ser "" (filtrado abajo) o una entrada
        // real de BrandExtractor.MARCAS — nunca un nombre de sitio.
        var byMarca = r.productos().stream()
            .filter(p -> p.marca() != null && !p.marca().isBlank())
            .filter(p -> rubro == null || rubro.isBlank()
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> q == null || q.isBlank()
                || p.marca().toLowerCase().contains(q.toLowerCase()))
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.marca().trim()
            ));

        var entries = new java.util.ArrayList<>(byMarca.entrySet());
        entries.sort(switch (sort) {
            case "precio_asc"  -> java.util.Comparator.comparingDouble(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().stream().mapToDouble(Product::precio).average().orElse(0));
            case "precio_desc" -> java.util.Comparator.comparingDouble(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().stream().mapToDouble(Product::precio).average().orElse(0)).reversed();
            default -> java.util.Comparator.comparingInt(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().size()).reversed();
        });

        var result = MAPPER.createArrayNode();
        entries.stream()
            .filter(e -> e.getValue().size() >= 2)  // al menos 2 productos por marca
            .limit(100)
            .forEach(entry -> {
                String marca = entry.getKey();
                var   prods  = entry.getValue();

                double[] sortedP = prods.stream().mapToDouble(Product::precio).sorted().toArray();
                double mediana   = sortedP[sortedP.length / 2];
                String rubroVal  = prods.get(0).rubro() != null ? prods.get(0).rubro() : "indumentaria";

                String topCats = prods.stream()
                    .filter(p -> p.categoria() != null && !p.categoria().isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(
                        Product::categoria, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(
                        (java.util.Map.Entry<String,Long> e2) -> e2.getValue()).reversed())
                    .limit(3).map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.joining(", "));

                Product best = prods.stream()
                    .filter(p -> p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .min(java.util.Comparator.comparingInt(
                        p -> p.ml() != null && p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .orElse(prods.get(0));

                String img = best.imagenUrl() != null ? best.imagenUrl() : "";
                if (img.startsWith("//")) img = "https:" + img;

                var node = result.addObject();
                node.put("marca",     marca);
                node.put("count",     prods.size());
                node.put("rubro",     rubroVal);
                node.put("img",       img);
                node.put("mediana",   (long) mediana);
                node.put("precioMin", (long) sortedP[0]);
                node.put("precioMax", (long) sortedP[sortedP.length-1]);
                node.put("topCats",   topCats);

                var pNode = node.putObject("bestPick");
                pNode.put("nombre", safe(best.nombre()));
                pNode.put("precio", best.precio());
                pNode.put("url",    safe(best.url()));
                String pImg = safe(best.imagenUrl());
                if (pImg.startsWith("//")) pImg = "https:" + pImg;
                pNode.put("img",    pImg);
                if (best.ml() != null) {
                    pNode.put("badge",  safe(best.ml().badge()));
                    pNode.put("scoreP", best.ml().scoreP());
                }
            });
        return ResponseEntity.ok(result);
    }

    // ─── Mejores picks por categoría ─────────────────────────────────────────────

    ResponseEntity<Object> mejoresPorCategoria(String rubro) {

        var r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

        // Agrupar productos por categoría
        java.util.Map<String, java.util.List<Product>> byCat = r.productos().stream()
            .filter(p -> p.categoria() != null && !p.categoria().isBlank())
            .filter(p -> rubro == null || rubro.isBlank()
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> !"infantil".equalsIgnoreCase(p.genero() == null ? "" : p.genero().trim()))
            .collect(java.util.stream.Collectors.groupingBy(Product::categoria));

        var result = MAPPER.createArrayNode();

        byCat.entrySet().stream()
            .sorted((a,b) -> b.getValue().size() - a.getValue().size())
            .limit(40)
            .forEach(entry -> {
                String cat   = entry.getKey();
                var   prods  = entry.getValue();
                if (prods.isEmpty()) return;

                // 1. Mejor precio/calidad: menor composite score con imagen
                Product mejor = prods.stream()
                    .filter(p -> p.ml() != null && p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .min(java.util.Comparator.comparingInt(
                        p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .orElse(prods.get(0));

                // 2. Premium accesible: segmento premium o standard, composite 30-65
                Product premium = prods.stream()
                    .filter(p -> p.ml() != null
                        && ("premium".equals(p.ml().segment()) || "standard".equals(p.ml().segment()))
                        && p.ml().scoreP() >= 30 && p.ml().scoreP() <= 65
                        && p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .findFirst().orElse(null);

                // 3. Mínimo histórico
                Product histLow = prods.stream()
                    .filter(p -> p.ml() != null && p.ml().badges() != null
                        && p.ml().badges().contains("all_time_low"))
                    .findFirst().orElse(null);

                // 4. Oferta real
                Product oferta = prods.stream()
                    .filter(p -> p.ml() != null && p.ml().badges() != null
                        && p.ml().badges().contains("verified_deal"))
                    .findFirst().orElse(null);

                // Stats de la categoría — computado sobre precio unitario (pack-aware),
                // no sobre precio de estantería, para no penalizar packs genuinos.
                double mediana = prods.stream().mapToDouble(ProductJson::precioUnitario)
                    .sorted().skip(prods.size()/2).findFirst().orElse(0);
                String imgCat = mejor.imagenUrl() != null ? mejor.imagenUrl() : "";
                if (imgCat.startsWith("//")) imgCat = "https:" + imgCat;
                String rubroVal = mejor.rubro() != null ? mejor.rubro() : "indumentaria";

                var node = result.addObject();
                node.put("categoria", cat);
                node.put("count",     prods.size());
                node.put("rubro",     rubroVal);
                node.put("imgCat",    imgCat);
                node.put("mediana",   Math.round(mediana));

                var picks = node.putArray("picks");
                java.util.Set<String> incluidos = new java.util.HashSet<>();
                // Highlights curados primero (preservan su etiqueta semántica).
                addMejorPickDedup(picks, mejor,   "valor",    "Mejor precio/calidad", incluidos);
                addMejorPickDedup(picks, premium, "premium",  "Premium accesible",    incluidos);
                addMejorPickDedup(picks, histLow, "histLow",  "Mínimo histórico",     incluidos);
                addMejorPickDedup(picks, oferta,  "oferta",   "Oferta real",          incluidos);
                // Rellenar hasta MAX_PICKS_POR_CATEGORIA con los siguientes mejores por
                // scoreP (con imagen). Así los packs con buen precio unitario entran
                // integrados en la categoría en vez de quedar afuera por el único cupo
                // de "valor" (scoreP ya es unit-price-aware en ml_pipeline).
                java.util.List<Product> ordenados = prods.stream()
                    .filter(p -> p.ml() != null && p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .sorted(java.util.Comparator.comparingInt(
                        p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .collect(java.util.stream.Collectors.toList());
                for (Product p : ordenados) {
                    if (picks.size() >= MAX_PICKS_POR_CATEGORIA) break;
                    addMejorPickDedup(picks, p, "top", "Buena compra", incluidos);
                }
            });

        return ResponseEntity.ok(result);
    }

    /**
     * Agrega un pick evitando duplicados por URL (un producto puede calificar para
     * varios highlights, p.ej. ser el "valor" y además "oferta_real"; se muestra
     * una sola vez con la primera etiqueta que le tocó). Ignora {@code null}.
     */
    private void addMejorPickDedup(com.fasterxml.jackson.databind.node.ArrayNode arr,
                                   Product p, String tipo, String label,
                                   java.util.Set<String> incluidos) {
        if (p == null) return;
        String url = p.url() != null ? p.url() : "";
        if (!url.isBlank() && !incluidos.add(url)) return; // ya incluido
        addMejorPick(arr, p, tipo, label);
    }

    private void addMejorPick(com.fasterxml.jackson.databind.node.ArrayNode arr,
                              Product p, String tipo, String label) {
        var n = arr.addObject();
        n.put("tipo",   tipo);
        n.put("label",  label);
        n.put("nombre", safe(p.nombre()));
        n.put("precio", p.precio());
        n.put("cantidadUnidades", p.cantidadUnidades());
        n.put("esPack",     p.esPack());
        n.put("precioUnitario", ProductJson.precioUnitario(p));
        n.put("url",    safe(p.url()));
        String img = safe(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        n.put("img",    img);
        n.put("sitio",  safe(p.sitio()));
        n.put("marca",  safe(p.marca()));
        if (p.ml() != null) {
            n.put("scoreP",  p.ml().scoreP());
            n.put("badge",   safe(p.ml().badge()));
            n.put("segment", safe(p.ml().segment()));
            n.put("pctil",   p.ml().pctilCategoria());
        }
        if (p.precioOriginal() != null)
            n.put("precioOrig", p.precioOriginal());
    }
}
