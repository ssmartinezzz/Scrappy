package ar.scraper.web.cache;

import ar.scraper.catalog.PoliticaStock;
import ar.scraper.catalog.ProductKey;
import ar.scraper.classification.SiteClassification;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.json.ProductJson;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.apache.commons.lang3.StringUtils;

import ar.scraper.api.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MarcasPicksView {

    private static final int MAX_PICKS_POR_CATEGORIA = 20;
    private static final String SUB_COMPRESION = "compresión";
    private static final String CATEGORIA_COMPRESION = "Remera de compresión";

    private MarcasPicksView() {}

    private static String safe(String s) { return ProductJson.safe(s); }

    static final String ORDEN_NOMBRE = "nombre";
    private static final String ORDEN_COUNT = "count";
    private static final String ORDEN_PRECIO_ASC = "precio_asc";
    private static final String ORDEN_PRECIO_DESC = "precio_desc";

    static final int MARCAS_POR_DEFECTO = 48;

    /** A brand in the paged ranking; {@code clave} is its accent- and case-insensitive Spanish collation key. */
    record MarcaRank(MarcasPicksDtos.Marca marca, double precioMedio, CollationKey clave) {}

    private static final Collator COLLATOR_ES = collator();

    private static Collator collator() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("es"));
        c.setStrength(Collator.PRIMARY);
        return c;
    }

    private static MarcaRank rank(MarcasPicksDtos.Marca marca, double precioMedio) {
        return new MarcaRank(marca, precioMedio, COLLATOR_ES.getCollationKey(marca.getMarca()));
    }

    private static final Comparator<MarcaRank> POR_NOMBRE =
        Comparator.comparing(MarcaRank::clave).thenComparing(m -> m.marca().getMarca());

    /** Every order is total: the brand name (collation key, then raw text) breaks every tie. */
    private static Comparator<MarcaRank> ordenDeMarcas(String sort) {
        return switch (sort) {
            case ORDEN_NOMBRE -> POR_NOMBRE;
            case ORDEN_COUNT -> Comparator.comparingInt((MarcaRank m) -> m.marca().getCount()).reversed()
                .thenComparing(POR_NOMBRE);
            case ORDEN_PRECIO_ASC -> Comparator.comparingDouble(MarcaRank::precioMedio).thenComparing(POR_NOMBRE);
            case ORDEN_PRECIO_DESC -> Comparator.comparingDouble(MarcaRank::precioMedio).reversed()
                .thenComparing(POR_NOMBRE);
            default -> throw solicitudInvalida("sort inválido");
        };
    }

    /**
     * Every brand with at least two products, sorted by {@code sort} ({@code nombre}, {@code count},
     * {@code precio_asc}, {@code precio_desc}; blank means {@code nombre}, anything else is a 400). Built once per
     * snapshot and cached by the caller; a page is a binary search over it.
     */
    static List<MarcaRank> marcasRanking(AggregatedResult r, String rubro, String q, String sort) {
        Comparator<MarcaRank> orden = ordenDeMarcas(StringUtils.defaultIfBlank(sort, ORDEN_NOMBRE));
        var byMarca = r.productos().stream()
            .filter(p -> StringUtils.isNotBlank(p.marca()))
            .filter(p -> StringUtils.isBlank(rubro)
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> StringUtils.isBlank(q)
                || p.marca().toLowerCase().contains(q.toLowerCase()))
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.marca().trim()
            ));

        List<MarcaRank> result = new ArrayList<>();
        byMarca.forEach((marca, prods) -> {
            if (prods.size() >= 2) {
                result.add(rank(toMarca(marca, prods), prods.stream().mapToDouble(Product::precio).average().orElse(0)));
            }
        });
        result.sort(orden);
        return List.copyOf(result);
    }

    private static MarcasPicksDtos.Marca toMarca(String marca, List<Product> prods) {
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
        return new MarcasPicksDtos.Marca(marca, prods.size(), rubroVal, img,
                (long) mediana, (long) sortedP[0], (long) sortedP[sortedP.length - 1],
                topCats, pick);
    }

    /**
     * One keyset page of brands: the {@code limit} (default 48, clamped to 1..100) brands strictly after
     * {@code cursor}, found by binary search. The cursor carries the sort and the full key, so one issued under
     * another sort is a 400.
     */
    static MarcasPicksDtos.MarcasPagina paginaMarcas(List<MarcaRank> ranking, String sort, String cursor, int limit) {
        String orden = StringUtils.defaultIfBlank(sort, ORDEN_NOMBRE);
        Comparator<MarcaRank> cmp = ordenDeMarcas(orden);
        int n = Math.max(1, Math.min(limit, PAGINA_MAXIMA));
        int desde = StringUtils.isBlank(cursor) ? 0 : despuesDe(ranking, decodificarMarca(cursor, orden), cmp);
        int hasta = Math.min(ranking.size(), desde + n);
        List<MarcasPicksDtos.Marca> marcas = new ArrayList<>(hasta - desde);
        for (int i = desde; i < hasta; i++) marcas.add(ranking.get(i).marca());
        String siguiente = hasta < ranking.size() && hasta > desde ? codificarMarca(orden, ranking.get(hasta - 1)) : null;
        return new MarcasPicksDtos.MarcasPagina(marcas, siguiente, ranking.size());
    }

    private static String codificarMarca(String sort, MarcaRank m) {
        return codificar(sort + "|" + m.marca().getCount() + "|" + m.precioMedio() + "|" + m.marca().getMarca());
    }

    private static MarcaRank decodificarMarca(String cursor, String sort) {
        String[] partes = decodificarTexto(cursor).split("\\|", 4);
        try {
            if (partes.length < 4 || !partes[0].equals(sort) || partes[3].isEmpty()) {
                throw new IllegalArgumentException("cursor de otro orden o incompleto");
            }
            var marca = new MarcasPicksDtos.Marca();
            marca.setMarca(partes[3]);
            marca.setCount(Integer.parseInt(partes[1]));
            return rank(marca, Double.parseDouble(partes[2]));
        } catch (IllegalArgumentException e) {
            throw solicitudInvalida("cursor inválido");
        }
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
        return mejores(r, rubro, gymrat, genero, PoliticaStock.porDefecto());
    }

    static List<MarcasPicksDtos.MejoresCategoria> mejores(AggregatedResult r, String rubro, boolean gymrat,
                                                          String genero, PoliticaStock politica) {
        java.util.Map<String, java.util.List<Product>> byCat = elegibles(r, rubro, gymrat, genero)
            .collect(java.util.stream.Collectors.groupingBy(p -> etiquetaDeCategoria(p, gymrat)));

        List<MarcasPicksDtos.MejoresCategoria> result = new ArrayList<>();

        byCat.entrySet().stream()
            .sorted((a,b) -> b.getValue().size() - a.getValue().size())
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
                addMejorPickDedup(picks, mejor,        "valor",    "Mejor precio/calidad", incluidos, politica);
                addMejorPickDedup(picks, sel.premium,  "premium",  "Premium accesible",    incluidos, politica);
                addMejorPickDedup(picks, sel.histLow,  "histLow",  "Mínimo histórico",     incluidos, politica);
                addMejorPickDedup(picks, sel.oferta,   "oferta",   "Oferta real",          incluidos, politica);
                for (Product p : sel.mejoresPorScore()) {
                    if (picks.size() >= MAX_PICKS_POR_CATEGORIA) break;
                    addMejorPickDedup(picks, p, "top", "Buena compra", incluidos, politica);
                }
                result.add(new MarcasPicksDtos.MejoresCategoria(cat, prods.size(), rubroVal, imgCat,
                        Math.round(mediana), picks));
            });

        return result;
    }

    private static java.util.stream.Stream<Product> elegibles(AggregatedResult r, String rubro, boolean gymrat,
                                                              String genero) {
        return r.productos().stream()
            .filter(p -> StringUtils.isNotBlank(p.categoria()))
            .filter(p -> !gymrat || !"Otros".equals(p.categoria()))
            .filter(p -> StringUtils.isBlank(rubro)
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> !gymrat || (p.esGymrat() && SiteClassification.esMarcaGymPicks(p.sitio(), p.marca())))
            .filter(ar.scraper.outfits.EligibilityPolicy::elegible)
            .filter(p -> aplicaAGenero(p, genero));
    }

    /** A product in the paged ranking: its sort key is {@code (clave, url)}, a total order because urls are unique. */
    record Ranked(int clave, String url, Product producto) {}

    private static final java.util.Comparator<Ranked> ORDEN_RANKING =
        java.util.Comparator.comparingInt(Ranked::clave).thenComparing(Ranked::url);

    /**
     * Every eligible product of every category, ranked like {@code mejores} ranks its picks ({@code scoreP}, 999 when
     * unscored) with the url as tie-break, one entry per url (its best key). Products without a url cannot be
     * addressed by a cursor and are left out. Built once per snapshot and cached by the caller.
     */
    static Map<String, List<Ranked>> ranking(AggregatedResult r, String rubro, boolean gymrat, String genero) {
        Map<String, Map<String, Ranked>> porCategoria = new java.util.HashMap<>();
        elegibles(r, rubro, gymrat, genero).forEach(p -> {
            String url = StringUtils.defaultString(p.url());
            if (url.isBlank()) return;
            int clave = p.ml() != null ? clave(p) : 999;
            porCategoria.computeIfAbsent(etiquetaDeCategoria(p, gymrat), k -> new java.util.HashMap<>())
                .merge(url, new Ranked(clave, url, p), (a, b) -> ORDEN_RANKING.compare(b, a) < 0 ? b : a);
        });
        Map<String, List<Ranked>> out = new java.util.HashMap<>();
        porCategoria.forEach((cat, porUrl) -> {
            List<Ranked> lista = new ArrayList<>(porUrl.values());
            lista.sort(ORDEN_RANKING);
            out.put(cat, List.copyOf(lista));
        });
        return Map.copyOf(out);
    }

    static final int PAGINA_POR_DEFECTO = 24;
    static final int PAGINA_MAXIMA = 100;

    /**
     * One keyset page: the {@code limit} products strictly after {@code cursor} (base64url of {@code clave|url}, found
     * by binary search, never by skipping N rows). {@code nextCursor} is null on the last page.
     */
    static MarcasPicksDtos.PicksPagina pagina(List<Ranked> ranking, String cursor, int limit) {
        return pagina(ranking, cursor, limit, PoliticaStock.porDefecto());
    }

    static MarcasPicksDtos.PicksPagina pagina(List<Ranked> ranking, String cursor, int limit, PoliticaStock politica) {
        int n = Math.max(1, Math.min(limit, PAGINA_MAXIMA));
        int desde = StringUtils.isBlank(cursor) ? 0 : despuesDe(ranking, decodificar(cursor), ORDEN_RANKING);
        int hasta = Math.min(ranking.size(), desde + n);
        List<MarcasPicksDtos.Pick> picks = new ArrayList<>(hasta - desde);
        for (int i = desde; i < hasta; i++) picks.add(toPick(ranking.get(i).producto(), "top", "Buena compra", politica));
        String siguiente = hasta < ranking.size() && hasta > desde ? codificar(ranking.get(hasta - 1)) : null;
        return new MarcasPicksDtos.PicksPagina(picks, siguiente, ranking.size());
    }

    private static <T> int despuesDe(List<T> ranking, T clave, Comparator<T> orden) {
        int lo = 0;
        int hi = ranking.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (orden.compare(ranking.get(mid), clave) <= 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static String codificar(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodificarTexto(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw solicitudInvalida("cursor inválido");
        }
    }

    private static ApiException solicitudInvalida(String mensaje) {
        return new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", mensaje);
    }

    private static String codificar(Ranked r) {
        return codificar(r.clave() + "|" + r.url());
    }

    private static Ranked decodificar(String cursor) {
        String raw = decodificarTexto(cursor);
        int barra = raw.indexOf('|');
        try {
            if (barra < 1 || barra == raw.length() - 1) throw new IllegalArgumentException("sin separador");
            return new Ranked(Integer.parseInt(raw.substring(0, barra)), raw.substring(barra + 1), null);
        } catch (IllegalArgumentException e) {
            throw solicitudInvalida("cursor inválido");
        }
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
                                   java.util.Set<String> incluidos, PoliticaStock politica) {
        if (p == null) return;
        String url = StringUtils.defaultString(p.url());
        if (!url.isBlank() && !incluidos.add(url)) return;
        picks.add(toPick(p, tipo, label, politica));
    }

    private static MarcasPicksDtos.Pick toPick(Product p, String tipo, String label, PoliticaStock politica) {
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
        n.setKey(StringUtils.trimToNull(ProductKey.of(p.url())));
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
        n.setStock(politica.estado(p).wire());
        n.setVistoEnStockAt(ProductJson.iso(p.vistoEnStockAt()));
        n.setStockChequeadoAt(ProductJson.iso(p.stockChequeadoAt()));
        n.setSubCategoria(StringUtils.trimToNull(p.subCategoria()));
        return n;
    }
}
