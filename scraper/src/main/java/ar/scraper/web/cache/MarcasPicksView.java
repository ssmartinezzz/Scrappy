package ar.scraper.web.cache;

import ar.scraper.classification.SiteClassification;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.json.ProductJson;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

final class MarcasPicksView {

    private static final int MAX_PICKS_POR_CATEGORIA = 10;
    private static final String SUB_COMPRESION = "compresión";
    private static final String CATEGORIA_COMPRESION = "Remera de compresión";

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

                String img = StringUtils.defaultString(best.imagenUrl());
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
        return mejores(r, rubro, false, "");
    }

    static List<MarcasPicksDtos.MejoresCategoria> mejores(AggregatedResult r, String rubro, boolean gymrat) {
        return mejores(r, rubro, gymrat, "");
    }

    /** {@code genero} is {@code hombre}, {@code mujer} or blank; unisex and unlabelled products fit both. */
    static List<MarcasPicksDtos.MejoresCategoria> mejores(AggregatedResult r, String rubro, boolean gymrat,
                                                          String genero) {
        java.util.Map<String, java.util.List<Product>> byCat = r.productos().stream()
            .filter(p -> StringUtils.isNotBlank(p.categoria()))
            .filter(p -> !gymrat || !"Otros".equals(p.categoria()))
            .filter(p -> StringUtils.isBlank(rubro)
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> !gymrat || (p.esGymrat() && SiteClassification.esMarcaGymPicks(p.sitio(), p.marca())))
            .filter(p -> !"infantil".equalsIgnoreCase(p.genero() == null ? "" : p.genero().trim()))
            .filter(p -> aplicaAGenero(p, genero))
            .collect(java.util.stream.Collectors.groupingBy(p -> etiquetaDeCategoria(p, gymrat)));

        List<MarcasPicksDtos.MejoresCategoria> result = new ArrayList<>();

        byCat.entrySet().stream()
            .sorted((a,b) -> b.getValue().size() - a.getValue().size())
            .limit(40)
            .forEach(entry -> {
                String cat   = entry.getKey();
                var   prods  = entry.getValue();
                if (prods.isEmpty()) return;

                Seleccion sel = Seleccion.de(prods);
                Product mejor = sel.mejor != null ? sel.mejor : prods.get(0);

                // Unit price (pack-aware), not shelf price, so genuine packs are not penalised.
                double mediana = sel.medianaPrecioUnitario();
                String imgCat = StringUtils.defaultString(mejor.imagenUrl());
                if (imgCat.startsWith("//")) imgCat = "https:" + imgCat;
                String rubroVal = mejor.rubro() != null ? mejor.rubro() : "indumentaria";

                List<MarcasPicksDtos.Pick> picks = new ArrayList<>();
                java.util.Set<String> incluidos = new java.util.HashSet<>();
                // Curated highlights first (they keep their label), then fill with the next best by
                // scoreP so packs with a good unit price are not shut out of "valor".
                addMejorPickDedup(picks, mejor,        "valor",    "Mejor precio/calidad", incluidos);
                addMejorPickDedup(picks, sel.premium,  "premium",  "Premium accesible",    incluidos);
                addMejorPickDedup(picks, sel.histLow,  "histLow",  "Mínimo histórico",     incluidos);
                addMejorPickDedup(picks, sel.oferta,   "oferta",   "Oferta real",          incluidos);
                for (Product p : sel.mejoresPorScore()) {
                    if (picks.size() >= MAX_PICKS_POR_CATEGORIA) break;
                    addMejorPickDedup(picks, p, "top", "Buena compra", incluidos);
                }
                result.add(new MarcasPicksDtos.MejoresCategoria(cat, prods.size(), rubroVal, imgCat,
                        Math.round(mediana), picks));
            });

        return result;
    }

    private static boolean aplicaAGenero(Product p, String genero) {
        if (StringUtils.isBlank(genero)) return true;
        String g = StringUtils.defaultString(p.genero()).trim();
        return g.isEmpty() || "unisex".equalsIgnoreCase(g) || genero.equalsIgnoreCase(g);
    }

    private static String etiquetaDeCategoria(Product p, boolean gymrat) {
        return gymrat && "Remera".equals(p.categoria()) && SUB_COMPRESION.equals(p.subCategoria())
            ? CATEGORIA_COMPRESION : p.categoria();
    }

    private static int clave(Product p) {
        return p.ml().scoreP() > 0 ? p.ml().scoreP() : 999;
    }

    /**
     * One pass over a category: the curated highlights, the unit-price median and the best
     * {@link #MAX_PICKS_POR_CATEGORIA} distinct urls by score. The highlights keep encounter order
     * (first match wins) and the ranking is stable on ties, as the sort it replaces was.
     */
    private static final class Seleccion {
        Product mejor;
        Product premium;
        Product histLow;
        Product oferta;
        private double[] precios;
        private final List<Product> top = new ArrayList<>(MAX_PICKS_POR_CATEGORIA + 1);
        private final List<Integer> topIdx = new ArrayList<>(MAX_PICKS_POR_CATEGORIA + 1);

        static Seleccion de(List<Product> prods) {
            Seleccion s = new Seleccion();
            s.precios = new double[prods.size()];
            int i = 0;
            for (Product p : prods) {
                s.precios[i] = ProductJson.precioUnitario(p);
                if (p.ml() != null) {
                    boolean conImagen = StringUtils.isNotBlank(p.imagenUrl());
                    if (conImagen && (s.mejor == null || clave(p) < clave(s.mejor))) s.mejor = p;
                    if (conImagen && s.premium == null && esPremiumAccesible(p)) s.premium = p;
                    if (s.histLow == null && tieneBadge(p, "all_time_low")) s.histLow = p;
                    if (s.oferta == null && tieneBadge(p, "verified_deal")) s.oferta = p;
                    if (conImagen) s.ofrecer(p, i);
                }
                i++;
            }
            return s;
        }

        private static boolean esPremiumAccesible(Product p) {
            return ("premium".equals(p.ml().segment()) || "standard".equals(p.ml().segment()))
                && p.ml().scoreP() >= 30 && p.ml().scoreP() <= 65;
        }

        private static boolean tieneBadge(Product p, String badge) {
            return p.ml().badges() != null && p.ml().badges().contains(badge);
        }

        private boolean antes(Product p, int idx, Product q, int qIdx) {
            int c = Integer.compare(clave(p), clave(q));
            return c < 0 || (c == 0 && idx < qIdx);
        }

        private void ofrecer(Product p, int idx) {
            String url = StringUtils.defaultString(p.url());
            if (!url.isBlank()) {
                for (int k = 0; k < top.size(); k++) {
                    if (url.equals(StringUtils.defaultString(top.get(k).url()))) {
                        if (antes(p, idx, top.get(k), topIdx.get(k))) { top.remove(k); topIdx.remove(k); insertar(p, idx); }
                        return;
                    }
                }
            }
            if (top.size() < MAX_PICKS_POR_CATEGORIA
                    || antes(p, idx, top.get(top.size() - 1), topIdx.get(topIdx.size() - 1))) {
                insertar(p, idx);
                if (top.size() > MAX_PICKS_POR_CATEGORIA) {
                    top.remove(top.size() - 1);
                    topIdx.remove(topIdx.size() - 1);
                }
            }
        }

        private void insertar(Product p, int idx) {
            int pos = top.size();
            while (pos > 0 && antes(p, idx, top.get(pos - 1), topIdx.get(pos - 1))) pos--;
            top.add(pos, p);
            topIdx.add(pos, idx);
        }

        List<Product> mejoresPorScore() { return top; }

        double medianaPrecioUnitario() {
            java.util.Arrays.sort(precios);
            return precios[precios.length / 2];
        }
    }

    private static void addMejorPickDedup(List<MarcasPicksDtos.Pick> picks,
                                   Product p, String tipo, String label,
                                   java.util.Set<String> incluidos) {
        if (p == null) return;
        String url = StringUtils.defaultString(p.url());
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
