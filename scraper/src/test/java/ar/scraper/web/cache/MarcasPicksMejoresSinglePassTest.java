package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.json.ProductJson;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-category selection must equal the straightforward multi-pass definition it replaced. */
class MarcasPicksMejoresSinglePassTest {

    private static final int MAX = 10;

    private static final String[] SEGMENTS = {"premium", "standard", "budget", ""};
    private static final String[][] BADGES = {{}, {"all_time_low"}, {"verified_deal"}, {"all_time_low", "verified_deal"}};

    private static List<Product> random(long seed) {
        Random rnd = new Random(seed);
        List<Product> out = new ArrayList<>();
        String[] cats = {"Remera", "Short", "Calza", "Buzo"};
        int n = 1 + rnd.nextInt(60);
        for (int i = 0; i < n; i++) {
            boolean conMl = rnd.nextInt(5) != 0;
            var ml = conMl
                    ? new Product.MlScore(rnd.nextInt(5) == 0 ? 0 : 20 + rnd.nextInt(10) * 8,
                            List.of(BADGES[rnd.nextInt(BADGES.length)]), false, "estable", rnd.nextInt(100), 0.0,
                            SEGMENTS[rnd.nextInt(SEGMENTS.length)])
                    : null;
            out.add(Product.builder()
                    .sitio("Fuark").nombre("P" + i).precio(100 + rnd.nextInt(900))
                    .url(rnd.nextInt(6) == 0 ? "https://s/dup" + rnd.nextInt(3) : rnd.nextInt(15) == 0 ? "" : "https://s/" + i)
                    .imagenUrl(rnd.nextInt(6) == 0 ? "" : "//img/" + i).categoria(cats[rnd.nextInt(cats.length)])
                    .genero("unisex").talles(List.of("M")).ml(ml).marca("Fuark").rubro("indumentaria")
                    .gymrat(true).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                    .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).subCategoria("")
                    .visual(Product.VisualAttrs.EMPTY).build());
        }
        return out;
    }

    @Test
    void matchesTheMultiPassDefinitionOnRandomCatalogs() {
        for (long seed = 0; seed < 300; seed++) {
            var r = new AggregatedResult(random(seed), Map.of(), Map.of(), null, 0, 0);
            var esperado = referencia(r);
            var real = MarcasPicksView.mejores(r, "", false);
            assertThat(real).as("seed " + seed).usingRecursiveComparison().isEqualTo(esperado);
        }
    }

    private static List<MarcasPicksDtos.MejoresCategoria> referencia(AggregatedResult r) {
        Map<String, List<Product>> byCat = r.productos().stream()
                .filter(p -> StringUtils.isNotBlank(p.categoria()))
                .collect(Collectors.groupingBy(Product::categoria));
        List<MarcasPicksDtos.MejoresCategoria> result = new ArrayList<>();
        byCat.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(40)
                .forEach(entry -> {
                    String cat = entry.getKey();
                    var prods = entry.getValue();
                    Product mejor = prods.stream()
                            .filter(p -> p.ml() != null && StringUtils.isNotBlank(p.imagenUrl()))
                            .min(Comparator.comparingInt(p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
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
                    double mediana = prods.stream().mapToDouble(ProductJson::precioUnitario)
                            .sorted().skip(prods.size() / 2).findFirst().orElse(0);
                    String imgCat = StringUtils.defaultString(mejor.imagenUrl());
                    if (imgCat.startsWith("//")) imgCat = "https:" + imgCat;
                    String rubroVal = mejor.rubro() != null ? mejor.rubro() : "indumentaria";
                    List<MarcasPicksDtos.Pick> picks = new ArrayList<>();
                    Set<String> incluidos = new HashSet<>();
                    add(picks, mejor, "valor", "Mejor precio/calidad", incluidos);
                    add(picks, premium, "premium", "Premium accesible", incluidos);
                    add(picks, histLow, "histLow", "Mínimo histórico", incluidos);
                    add(picks, oferta, "oferta", "Oferta real", incluidos);
                    List<Product> ordenados = prods.stream()
                            .filter(p -> p.ml() != null && StringUtils.isNotBlank(p.imagenUrl()))
                            .sorted(Comparator.comparingInt(p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                            .collect(Collectors.toList());
                    for (Product p : ordenados) {
                        if (picks.size() >= MAX) break;
                        add(picks, p, "top", "Buena compra", incluidos);
                    }
                    result.add(new MarcasPicksDtos.MejoresCategoria(cat, prods.size(), rubroVal, imgCat,
                            Math.round(mediana), picks));
                });
        return result;
    }

    private static void add(List<MarcasPicksDtos.Pick> picks, Product p, String tipo, String label, Set<String> incluidos) {
        if (p == null) return;
        String url = StringUtils.defaultString(p.url());
        if (!url.isBlank() && !incluidos.add(url)) return;
        var n = new MarcasPicksDtos.Pick();
        String img = ProductJson.safe(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        n.setTipo(tipo);
        n.setLabel(label);
        n.setNombre(ProductJson.safe(p.nombre()));
        n.setPrecio(p.precio());
        n.setCantidadUnidades(p.cantidadUnidades());
        n.setEsPack(p.esPack());
        n.setPrecioUnitario(ProductJson.precioUnitario(p));
        n.setUrl(ProductJson.safe(p.url()));
        n.setImg(img);
        n.setSitio(ProductJson.safe(p.sitio()));
        n.setMarca(ProductJson.safe(p.marca()));
        if (p.ml() != null) {
            n.setScoreP(p.ml().scoreP());
            n.setBadge(ProductJson.safe(p.ml().badge()));
            n.setSegment(ProductJson.safe(p.ml().segment()));
            n.setPctil(p.ml().pctilCategoria());
        }
        n.setPrecioOrig(p.precioOriginal());
        picks.add(n);
    }
}
