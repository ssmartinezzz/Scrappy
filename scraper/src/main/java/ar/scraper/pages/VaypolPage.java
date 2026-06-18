package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.*;

/**
 * Scraper para Vaypol / SomosCity — plataforma Next.js SSR.
 *
 * Estrategia principal: extraer __NEXT_DATA__ JSON embebido en el HTML.
 * Este objeto contiene todos los datos del producto incluyendo imagen,
 * género, talles y precio — sin necesidad de lazy loading ni DOM scraping.
 *
 * URL de listado: /{base}/productos/p/{page}
 * __NEXT_DATA__ path: props.pageProps.products[] o props.pageProps.data.products[]
 */
public class VaypolPage extends BasePage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGES = 250;
    private static final int WAIT_MS   = 400; // Next.js SSR — no lazy loading wait needed

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public VaypolPage(Page page, int timeoutMs,
                      String sitio, String baseUrl,
                      double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio     = sitio;
        this.baseUrl   = baseUrl.replaceAll("/+$", "");
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }



    // ─── Entry point ─────────────────────────────────────────────────────────

    public List<Product> scrapeAll() {
        List<Product> result = new ArrayList<>();
        String base = baseUrl.startsWith("http") ? baseUrl : "https://" + baseUrl;
        Set<String> urlsVistas = new HashSet<>();

        for (int p = 1; p <= MAX_PAGES; p++) {
            String url = base + "/productos/p/" + p;
            log.debug("[{}] page {}", sitio, p);
            try {
                navigateTo(url);
                page.waitForTimeout(WAIT_MS);

                // Estrategia 1: __NEXT_DATA__ JSON (preferido — tiene imágenes reales)
                List<Product> pagina = extraerDeNextData(base);

                // Estrategia 2: fallback links scraping si Next data no disponible
                if (pagina.isEmpty()) {
                    pagina = extraerDeLinks(base);
                    log.debug("[{}] p{}: links fallback → {} productos", sitio, p, pagina.size());
                }



                if (pagina.isEmpty()) {
                    log.info("[{}] página {} vacía → fin", sitio, p);
                    break;
                }

                // Detectar fin de catálogo por URLs repetidas
                Set<String> urlsPagina = new HashSet<>();
                for (Product prod : pagina) urlsPagina.add(prod.url());
                if (!urlsPagina.isEmpty() && urlsVistas.containsAll(urlsPagina)) {
                    log.info("[{}] página {} repetida → fin de catálogo", sitio, p);
                    break;
                }
                urlsVistas.addAll(urlsPagina);

                int antes = result.size();
                result.addAll(pagina);

                if (p % 5 == 0 || pagina.size() < 10) {
                    log.info("[{}] p{}: +{} (total {})", sitio, p, result.size()-antes, result.size());
                }
                if (pagina.size() < 10) break;

            } catch (Exception e) {
                log.warn("[{}] error p{}: {}", sitio, p, e.getMessage());
                break;
            }
        }
        // Enriquecer imágenes faltantes via HttpClient paralelo (NO Playwright)
        long sinImg = result.stream()
            .filter(p -> p.imagenUrl() == null || p.imagenUrl().isBlank()).count();
        if (sinImg > 0) {
            log.info("[{}] {} sin imagen → enriching via HttpClient paralelo", sitio, sinImg);
            result = enricherImagenesHttp(result);
        }

        log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
        return result;
    }

    // ─── Enriquecimiento de imágenes via HttpClient paralelo ─────────────────

    /**
     * Obtiene meta-og:image de páginas de detalle en PARALELO usando HttpClient.
     * 8 threads concurrentes → ~600 productos en ~10-15 segundos.
     * NO usa Playwright — solo GET HTTP simple, el meta-og:image está en los primeros 2KB.
     */
    private List<Product> enricherImagenesHttp(List<Product> productos) {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(6))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        var imgMap   = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var tasks    = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();

        for (Product p : productos) {
            if (p.imagenUrl() != null && !p.imagenUrl().isBlank()) continue;
            if (p.url()      == null  ||  p.url().isBlank())       continue;

            tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    var req = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(p.url()))
                            .GET()
                            .header("User-Agent", "Mozilla/5.0 (compatible)")
                            .header("Accept",     "text/html")
                            .build();
                    var resp = client.send(req,
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    String img = extraerOgImage(resp.body());
                    if (!img.isBlank()) imgMap.put(p.url(), img);
                } catch (Exception ignored) {}
            }, executor));
        }

        // Esperar máximo 45 segundos en total
        try {
            java.util.concurrent.CompletableFuture
                    .allOf(tasks.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(45, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        executor.shutdown();

        log.info("[{}] HttpClient: {}/{} imágenes obtenidas", sitio, imgMap.size(), tasks.size());

        return productos.stream().map(p -> {
            String img = imgMap.get(p.url());
            if (img != null && !img.isBlank())
                return new Product(p.sitio(), p.nombre(), p.precio(), p.precioOriginal(),
                        p.url(), img, p.categoria(), p.genero(), p.talles());
            return p;
        }).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Extrae og:image del HTML sin regex — string search pura.
     * Solo procesa los primeros 4KB donde está el <head>.
     */
    private String extraerOgImage(String html) {
        if (html == null || html.isBlank()) return "";
        String head = html.substring(0, Math.min(html.length(), 5000));
        int ogIdx = head.indexOf("og:image");
        if (ogIdx < 0) return "";
        String after = head.substring(ogIdx);
        int ci = after.indexOf("content=");
        if (ci < 0) return "";
        String fromC = after.substring(ci + 8);
        if (fromC.isEmpty()) return "";
        char q = fromC.charAt(0);
        if (q != '"' && q != '\'') return "";
        int end = fromC.indexOf(q, 1);
        if (end < 0) return "";
        String url = fromC.substring(1, end).trim();
        return url.startsWith("http") ? url : "";
    }

    // ─── Estrategia 1: __NEXT_DATA__ + CDN scan ─────────────────────────────────

    private List<Product> extraerDeNextData(String base) {
        try {
            // Extraer __NEXT_DATA__ completo Y hacer CDN scan para imágenes
            String jsonResult = (String) page.evaluate(
                "(function() {" +
                "  var el = document.getElementById('__NEXT_DATA__');" +
                "  if (!el) return null;" +
                "  var raw = el.textContent;" +
                "  var data;" +
                "  try { data = JSON.parse(raw); } catch(e) { return null; }" +
                // Escanear TODO el JSON buscando URLs del CDN de Vaypol
                "  var cdnRe = /production\\.cdn\\.vaypol\\.com\\/variants\\/([a-zA-Z0-9]+)\\/([a-f0-9]{60,})/g;" +
                "  var cdnUrls = [];" +
                "  var m;" +
                "  var str = raw;" +
                "  while ((m = cdnRe.exec(str)) !== null) {" +
                "    cdnUrls.push('https://production.cdn.vaypol.com/variants/' + m[1] + '/' + m[2]);" +
                "  }" +
                "  return JSON.stringify({ raw: raw, cdnUrls: cdnUrls });" +
                "})()"
            );
            if (jsonResult == null || jsonResult.isBlank()) return List.of();

            JsonNode wrapper = MAPPER.readTree(jsonResult);
            String rawJson   = wrapper.path("raw").asText("");
            JsonNode cdnArr  = wrapper.path("cdnUrls");

            // Armar mapa deduplicado de imágenes CDN encontradas
            // El primero de cada hash diferente es la imagen del producto
            // (los siguientes son diferentes variantes del mismo producto)
            List<String> cdnImgs = new ArrayList<>();
            Set<String> hashesVistas = new LinkedHashSet<>();
            if (cdnArr.isArray()) {
                for (JsonNode u : cdnArr) {
                    String url = u.asText("");
                    // El hash es la parte final (64 hex chars) — único por producto
                    String[] parts = url.split("/");
                    String hash = parts.length > 0 ? parts[parts.length - 1] : "";
                    if (!hash.isBlank() && hashesVistas.add(hash)) {
                        cdnImgs.add(url);
                    }
                }
            }
            log.info("[{}] __NEXT_DATA__ CDN scan: {} imágenes únicas", sitio, cdnImgs.size());

            // Parsear __NEXT_DATA__ para productos
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode products = encontrarProductsNode(root);

            if (products == null || !products.isArray() || products.isEmpty()) {
                log.debug("[{}] __NEXT_DATA__ sin array de productos", sitio);
                return List.of();
            }

            log.debug("[{}] __NEXT_DATA__ → {} productos", sitio, products.size());

            List<Product> result = new ArrayList<>();
            int imgIdx = 0;
            for (JsonNode p : products) {
                // Asignar imagen CDN por índice si está disponible
                String imgOverride = (imgIdx < cdnImgs.size()) ? cdnImgs.get(imgIdx++) : "";
                fromNextData(p, base, imgOverride).ifPresent(result::add);
            }
            return result;

        } catch (Exception e) {
            log.debug("[{}] __NEXT_DATA__ error: {}", sitio, e.getMessage());
            return List.of();
        }
    }

    /**
     * Busca el array de productos en distintas rutas del __NEXT_DATA__ JSON.
     * El path puede variar según la versión del sitio.
     */
    private JsonNode encontrarProductsNode(JsonNode root) {
        String[] paths = {
            "props/pageProps/products",
            "props/pageProps/data/products",
            "props/pageProps/catalog/products",
            "props/pageProps/items",
            "props/pageProps/data/items"
        };
        for (String path : paths) {
            JsonNode node = root;
            for (String key : path.split("/")) {
                node = node.path(key);
                if (node.isMissingNode()) break;
            }
            if (!node.isMissingNode() && node.isArray() && !node.isEmpty()) {
                return node;
            }
        }

        // Si no encontramos el array buscando por path conocido,
        // buscar recursivamente cualquier array de objetos con campo "slug" o "name"
        return buscarProductsRecursivo(root, 0);
    }

    private JsonNode buscarProductsRecursivo(JsonNode node, int depth) {
        if (depth > 5) return null;
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            if (first.isObject() && (first.has("slug") || first.has("name") || first.has("id"))) {
                return node;
            }
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = buscarProductsRecursivo(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Optional<Product> fromNextData(JsonNode p, String base, String imgOverride) {
        try {
            // Nombre
            String nombre = p.path("name").asText("");
            if (nombre.isBlank()) nombre = p.path("title").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();

            // URL
            String slug = p.path("slug").asText("");
            String url  = slug.isBlank() ? "" : base + "/" + slug;

            // Imagen — usar override del CDN scan si disponible, sino buscar en campos
            String img = (imgOverride != null && !imgOverride.isBlank()) ? imgOverride : "";
            JsonNode imgNode = img.isBlank() ? p.path("image") : null;
            if (imgNode == null) imgNode = MAPPER.createObjectNode();
            if (!imgNode.isMissingNode()) {
                if (imgNode.isTextual()) {
                    img = imgNode.asText("");
                } else if (imgNode.isObject()) {
                    img = imgNode.path("url").asText(imgNode.path("src").asText(""));
                }
            }
            if (img.isBlank()) {
                JsonNode imgs = p.path("images");
                if (imgs.isArray() && !imgs.isEmpty()) {
                    JsonNode first = imgs.get(0);
                    img = first.isTextual() ? first.asText()
                                            : first.path("url").asText(first.path("src").asText(""));
                }
            }
            if (img.startsWith("//")) img = "https:" + img;

            // Precio
            double precio    = 0;
            String precioOrig = null;

            // Vaypol Next.js puede tener precio en distintos campos
            JsonNode priceNode = p.path("price");
            if (!priceNode.isMissingNode()) {
                if (priceNode.isNumber()) {
                    precio = priceNode.asDouble();
                } else if (priceNode.isTextual()) {
                    Optional<Double> pv = parsePrecio(priceNode.asText());
                    if (pv.isPresent()) precio = pv.get();
                } else if (priceNode.isObject()) {
                    // {current: N, original: N}
                    precio = priceNode.path("current").asDouble(
                                priceNode.path("selling").asDouble(
                                    priceNode.path("sale").asDouble(0)));
                    double orig = priceNode.path("original").asDouble(
                                    priceNode.path("list").asDouble(0));
                    if (orig > precio && orig > 0) precioOrig = "$" + String.format("%.0f", orig);
                }
            }
            // Alternativas
            if (precio == 0) {
                precio = p.path("selling_price").asDouble(
                            p.path("sale_price").asDouble(
                                p.path("regular_price").asDouble(0)));
            }
            if (precio == 0) {
                Optional<Double> pv = parsePrecio(p.path("price_string").asText(""));
                if (pv.isPresent()) precio = pv.get();
            }
            if (precio == 0 || precio < precioMin || precio > precioMax) return Optional.empty();

            // Género
            String genero = normalizarGenero(
                p.path("gender").asText(
                    p.path("genre").asText("")));

            // Categoría
            String categoria = p.path("category").asText(
                                p.path("product_type").asText("")).trim();

            // Talles
            List<String> talles = extraerTalles(p);

            return Optional.of(new Product(
                    sitio, nombre.trim(), precio, precioOrig,
                    url, img, categoria, genero, talles));

        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ─── Estrategia 2: Links fallback (sin imágenes reales) ─────────────────

    private List<Product> extraerDeLinks(String base) {
        try {
            String json = (String) page.evaluate(buildLinksJs());
            if (json == null || json.equals("[]")) return List.of();

            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();

            List<Product> result = new ArrayList<>();
            for (JsonNode n : arr) {
                String nombre = n.path("nombre").asText("").trim();
                if (nombre.isBlank()) continue;
                Optional<Double> precio = parsePrecio(n.path("precio").asText(""));
                if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax) continue;
                String origStr = n.path("precioOrig").asText("");
                String href    = n.path("url").asText("");
                String url     = href.startsWith("http") ? href : base + href;
                String genero  = normalizarGenero(n.path("genero").asText(""));
                result.add(new Product(sitio, nombre, precio.get(),
                        origStr.isBlank() ? null : origStr,
                        url, "", "", genero, List.of()));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildLinksJs() {
        return "(function() {" +
            "var results=[];" +
            "var seen=new Set();" +
            "var GENEROS=['Hombre','Mujer','Unisex','Unisex Adulto','Nino','Nina','Bebe'];" +
            "var links=Array.from(document.querySelectorAll('a[href]')).filter(function(a){" +
            "  var h=a.getAttribute('href')||'';" +
            "  return h.replace(/[?#].*/,'').match(/-\\d{4,6}$/)!==null;" +
            "});" +
            "links.forEach(function(link){" +
            "  try{" +
            "    var rawHref=link.getAttribute('href')||'';" +
            "    var href=rawHref.charAt(0)==='/'?window.location.origin+rawHref:rawHref;" +
            "    href=href.replace(/[?#].*/,'');" +
            "    if(seen.has(href))return; seen.add(href);" +
            "    var slug=href.split('/').pop()||'';" +
            "    var nameSlug=slug.replace(/-\\d{4,6}$/,'');" +
            "    var nombre=nameSlug.split('-').map(function(w){" +
            "      return w.length>0?w.charAt(0).toUpperCase()+w.slice(1):w;" +
            "    }).join(' ').trim();" +
            "    if(!nombre||nombre.length<3)return;" +
            "    var txt=link.textContent||'';" +
            "    var priceMatches=[];" +
            "    var ci=0;" +
            "    while(ci<txt.length){" +
            "      if(txt[ci]==='$'){" +
            "        var cj=ci+1;" +
            "        while(cj<txt.length&&'0123456789.,'.indexOf(txt[cj])>=0)cj++;" +
            "        if(cj>ci+1){" +
            "          var raw=txt.substring(ci+1,cj);" +
            "          var num=parseFloat(raw.split('.').join('').replace(',','.'));" +
            "          if(num>500)priceMatches.push({raw:'$'+raw,val:num,pos:ci});" +
            "        }" +
            "        ci=cj;" +
            "      }else ci++;" +
            "    }" +
            "    var validPrices=priceMatches.filter(function(pm){" +
            "      if(pm.pos<3)return true;" +
            "      return txt.substring(pm.pos-3,pm.pos).indexOf(' x ')<0;" +
            "    });" +
            "    if(validPrices.length===0)return;" +
            "    validPrices.sort(function(a,b){return a.val-b.val;});" +
            "    var precioActual=validPrices[0].raw;" +
            "    var precioOrig=validPrices.length>1?validPrices[validPrices.length-1].raw:'';" +
            "    var genero='';" +
            "    for(var gi=0;gi<GENEROS.length;gi++){" +
            "      if(txt.indexOf(GENEROS[gi])>=0){genero=GENEROS[gi];break;}" +
            "    }" +
            "    results.push({nombre:nombre,precio:precioActual,precioOrig:precioOrig," +
            "      url:href,genero:genero});" +
            "  }catch(e){}" +
            "});" +
            "return JSON.stringify(results);" +
            "})()";
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<String> extraerTalles(JsonNode p) {
        List<String> talles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Buscar en variants[].size o similar
        JsonNode variants = p.path("variants");
        if (variants.isArray()) {
            for (JsonNode v : variants) {
                String t = v.path("size").asText(v.path("talle").asText("")).trim();
                if (!t.isBlank()) seen.add(t);
            }
        }
        // Buscar en sizes[] directamente
        JsonNode sizes = p.path("sizes");
        if (sizes.isArray()) {
            for (JsonNode s : sizes) {
                String t = s.isTextual() ? s.asText() : s.path("name").asText("").trim();
                if (!t.isBlank()) seen.add(t);
            }
        }
        // Campo "available_sizes" o "stock_sizes"
        JsonNode avail = p.path("available_sizes");
        if (avail.isArray()) {
            for (JsonNode s : avail) seen.add(s.asText().trim());
        }

        talles.addAll(seen);
        return talles;
    }

    private String normalizarGenero(String raw) {
        if (raw == null) return "";
        String r = raw.trim().toLowerCase();
        if (r.contains("hombre") || r.contains("masculino") || r.equals("male") || r.equals("men"))
            return "hombre";
        if (r.contains("mujer") || r.contains("femenino") || r.equals("female") || r.equals("women"))
            return "mujer";
        if (r.contains("unisex") || r.contains("neutro")) return "unisex";
        return "";
    }
}
