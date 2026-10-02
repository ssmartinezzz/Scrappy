package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.json.ProductJson;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

final class MarcasPicksView {

    private static final int MAX_PICKS_POR_CATEGORIA = 10;

    private MarcasPicksView() {}

    private static String safe(String s) { return ProductJson.safe(s); }

    static List<MarcasPicksDtos.Marca> marcas(AggregatedResult r, String rubro, String q, String sort) {
        var byMarca = r.productos().stream()
            .filter(p -> StringUtils.isNotBlank(p.marca()))
            .filter(p -> StringUtils.isBlank(rubro)
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> StringUtils.isBlank(q)
                || p.marca().toLowerCase().contains(q.toLowerCase()))
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.marca().trim()
            ));

        var entries = new java.util.ArrayList<>(byMarca.entrySet());
        java.util.function.ToDoubleFunction<java.util.Map.Entry<String, List<Product>>> precioMedio =
            e -> e.getValue().stream().mapToDouble(Product::precio).average().orElse(0);
        entries.sort(switch (sort) {
            case "precio_asc"  -> java.util.Comparator.comparingDouble(precioMedio);
            case "precio_desc" -> java.util.Comparator.comparingDouble(precioMedio).reversed();
            default -> java.util.Comparator.comparingInt(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().size()).reversed();
        });

        List<MarcasPicksDtos.Marca> result = new ArrayList<>();
        entries.stream()
            .filter(e -> e.getValue().size() >= 2)
            .limit(100)
            .forEach(entry -> {
                String marca = entry.getKey();
                var   prods  = entry.getValue();

                double[] sortedP = prods.stream().mapToDouble(Product::precio).sorted().toArray();
                double mediana   = sortedP[sortedP.length / 2];
                String rubroVal  = prods.get(0).rubro() != null ? prods.get(0).rubro() : "indumentaria";

                String topCats = prods.stream()
                    .filter(p -> StringUtils.isNotBlank(p.categoria()))
                    .collect(java.util.stream.Collectors.groupingBy(
                        Product::categoria, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(
                        (java.util.Map.Entry<String,Long> e2) -> e2.getValue()).reversed())
                    .limit(3).map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.joining(", "));

                Product best = prods.stream()
                    .filter(p -> StringUtils.isNotBlank(p.imagenUrl()))
                    .min(java.util.Comparator.comparingInt(
                        p -> p.ml() != null && p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .orElse(prods.get(0));

                String img = best.imagenUrl() != null ? best.imagenUrl() : "";
                if (img.startsWith("//")) img = "https:" + img;

                String pImg = safe(best.imagenUrl());
                if (pImg.startsWith("//")) pImg = "https:" + pImg;
                var pick = new MarcasPicksDtos.BestPick(safe(best.nombre()), best.precio(),
                        safe(best.url()), pImg,
                        best.ml() != null ? safe(best.ml().badge()) : null,
                        best.ml() != null ? best.ml().scoreP() : null);
                result.add(new MarcasPicksDtos.Marca(marca, prods.size(), rubroVal, img,
                        (long) mediana, (long) sortedP[0], (long) sortedP[sortedP.length - 1],
                        topCats, pick));
            });
        return result;
    }

    static List<MarcasPicksDtos.MejoresCategoria> mejores(AggregatedResult r, String rubro) {
        java.util.Map<String, java.util.List<Product>> byCat = r.productos().stream()
            .filter(p -> StringUtils.isNotBlank(p.categoria()))
            .filter(p -> StringUtils.isBlank(rubro)
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> !"infantil".equalsIgnoreCase(p.genero() == null ? "" : p.genero().trim()))
            .collect(java.util.stream.Collectors.groupingBy(Product::categoria));

        List<MarcasPicksDtos.MejoresCategoria> result = new ArrayList<>();

        byCat.entrySet().stream()
            .sorted((a,b) -> b.getValue().size() - a.getValue().size())
            .limit(40)
            .forEach(entry -> {
                String cat   = entry.getKey();
                var   prods  = entry.getValue();
                if (prods.isEmpty()) return;

                Product mejor = prods.stream()
                    .filter(p -> p.ml() != null && StringUtils.isNotBlank(p.imagenUrl()))
                    .min(java.util.Comparator.comparingInt(
                        p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .orElse(prods.get(0));

                Product premium = prods.stream()
                    .filter(p -> p.ml() != null
                        && ("premium".equals(p.ml().segment()) || "standard".equals(p.ml().segment()))
                        && p.ml().scoreP() >= 30 && p.ml().scoreP() <= 65
                        && StringUtils.isNotBlank(p.imagenUrl()))
                    .findFirst().orElse(null);

                Product histLow = prods.stream()
                    .filter(p -> p.ml() != null && p.ml().badges() != null
                        && p.ml().badges().contains("all_time_low"))
                    .findFirst().orElse(null);

                Product oferta = prods.stream()
                    .filter(p -> p.ml() != null && p.ml().badges() != null
                        && p.ml().badges().contains("verified_deal"))
                    .findFirst().orElse(null);

                // Unit price (pack-aware), not shelf price, so genuine packs are not penalised.
                double mediana = prods.stream().mapToDouble(ProductJson::precioUnitario)
                    .sorted().skip(prods.size()/2).findFirst().orElse(0);
                String imgCat = mejor.imagenUrl() != null ? mejor.imagenUrl() : "";
                if (imgCat.startsWith("//")) imgCat = "https:" + imgCat;
                String rubroVal = mejor.rubro() != null ? mejor.rubro() : "indumentaria";

                List<MarcasPicksDtos.Pick> picks = new ArrayList<>();
                java.util.Set<String> incluidos = new java.util.HashSet<>();
                // Curated highlights first (they keep their label), then fill with the next best by
                // scoreP so packs with a good unit price are not shut out of "valor".
                addMejorPickDedup(picks, mejor,   "valor",    "Mejor precio/calidad", incluidos);
                addMejorPickDedup(picks, premium, "premium",  "Premium accesible",    incluidos);
                addMejorPickDedup(picks, histLow, "histLow",  "Mínimo histórico",     incluidos);
                addMejorPickDedup(picks, oferta,  "oferta",   "Oferta real",          incluidos);
                java.util.List<Product> ordenados = prods.stream()
                    .filter(p -> p.ml() != null && StringUtils.isNotBlank(p.imagenUrl()))
                    .sorted(java.util.Comparator.comparingInt(
                        p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .collect(java.util.stream.Collectors.toList());
                for (Product p : ordenados) {
                    if (picks.size() >= MAX_PICKS_POR_CATEGORIA) break;
                    addMejorPickDedup(picks, p, "top", "Buena compra", incluidos);
                }
                result.add(new MarcasPicksDtos.MejoresCategoria(cat, prods.size(), rubroVal, imgCat,
                        Math.round(mediana), picks));
            });

        return result;
    }

    private static void addMejorPickDedup(List<MarcasPicksDtos.Pick> picks,
                                   Product p, String tipo, String label,
                                   java.util.Set<String> incluidos) {
        if (p == null) return;
        String url = p.url() != null ? p.url() : "";
        if (!url.isBlank() && !incluidos.add(url)) return;
        picks.add(toPick(p, tipo, label));
    }

    private static MarcasPicksDtos.Pick toPick(Product p, String tipo, String label) {
        String img = safe(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        var n = new MarcasPicksDtos.Pick();
        n.setTipo(tipo);
        n.setLabel(label);
        n.setNombre(safe(p.nombre()));
        n.setPrecio(p.precio());
        n.setCantidadUnidades(p.cantidadUnidades());
        n.setEsPack(p.esPack());
        n.setPrecioUnitario(ProductJson.precioUnitario(p));
        n.setUrl(safe(p.url()));
        n.setImg(img);
        n.setSitio(safe(p.sitio()));
        n.setMarca(safe(p.marca()));
        if (p.ml() != null) {
            n.setScoreP(p.ml().scoreP());
            n.setBadge(safe(p.ml().badge()));
            n.setSegment(safe(p.ml().segment()));
            n.setPctil(p.ml().pctilCategoria());
        }
        n.setPrecioOrig(p.precioOriginal());
        return n;
    }
}
