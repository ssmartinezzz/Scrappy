package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;

import java.util.*;

public class TiendanubePage extends BasePage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> PALABRAS_HOMBRE = Set.of(
            "hombre","hombres","masculino","masculina","men","man","male","caballero","varones");
    private static final Set<String> PALABRAS_MUJER  = Set.of(
            "mujer","mujeres","femenino","femenina","women","woman","female","dama","damas");
    private static final Set<String> PALABRAS_UNISEX = Set.of(
            "unisex","unisexo","neutro");

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public TiendanubePage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio     = sitio;
        this.baseUrl   = baseUrl;
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    public List<Product> scrapeAll() {
        String homeUrl = domain(baseUrl);
        List<Product> api = scrapeApi(homeUrl);
        if (!api.isEmpty()) {
            log.debug("[{}] API REST: {} productos", sitio, api.size());
            return api;
        }
        log.debug("[{}] API vacia, usando JS heuristico", sitio);
        return scrapeJs();
    }

    // ------------------------------------------------------------------
    // Estrategia 1: API REST
    // ------------------------------------------------------------------
    private List<Product> scrapeApi(String homeUrl) {
        List<Product> result = new ArrayList<>();
        try {
            navigateTo(homeUrl);
            String storeId = extractStoreId();
            if (storeId == null || storeId.isBlank()) {
                navigateTo(baseUrl);
                storeId = extractStoreId();
            }
            if (storeId == null || storeId.isBlank()) {
                log.debug("[{}] Store ID no encontrado", sitio);
                return result;
            }
            log.debug("[{}] Store ID: {}", sitio, storeId);

            String dom = domain(baseUrl);
            for (int p = 1; p <= 20; p++) {
                String apiUrl = dom + "/api/v1/" + storeId + "/products?per_page=200&page=" + p;
                navigateTo(apiUrl);
                String body = (String) page.evaluate("document.body.innerText");
                if (body == null || !body.trim().startsWith("[")) break;

                JsonNode arr = MAPPER.readTree(body);
                if (!arr.isArray() || arr.isEmpty()) break;

                for (JsonNode prod : arr) fromApi(prod, dom).ifPresent(result::add);
                log.debug("[{}] API p{}: {} acumulados", sitio, p, result.size());
                if (arr.size() < 200) break;
            }
        } catch (Exception e) {
            log.debug("[{}] API error: {}", sitio, e.getMessage());
        }
        return result;
    }

    private String extractStoreId() {
        try {
            return (String) page.evaluate(
                "(function() {" +
                // Prioridad 1: objeto LS nativo de Tiendanube (mas confiable, no puede estar en CDN)
                "  try {" +
                "    if (window.LS && window.LS.store && window.LS.store.id)" +
                "      return String(window.LS.store.id);" +
                "  } catch(e2) {}" +
                // Prioridad 2: data-store-id en body (temas TN modernos)
                "  var bodyId = document.body.getAttribute('data-store-id')" +
                "             || document.documentElement.getAttribute('data-store-id');" +
                "  if (bodyId && /^\\d{5,}$/.test(bodyId.trim())) return bodyId.trim();" +
                // Prioridad 3: meta tag
                "  var meta = document.querySelector('meta[name=\"store-id\"],meta[property=\"store:id\"]');" +
                "  if (meta) { var mc = meta.getAttribute('content'); if (mc && /^\\d{5,}$/.test(mc)) return mc; }" +
                // Prioridad 4: scripts INLINE (excluye CDN externos que comparten IDs)
                "  var scripts = Array.from(document.querySelectorAll('script:not([src])'));" +
                "  var pats = [" +
                "    /LS\\.store\\s*=\\s*\\{[^}]{0,200}[\"']id[\"']\\s*:\\s*(\\d{5,})/," +
                "    /[\"']store_id[\"']\\s*:\\s*(\\d{5,})/," +
                "    /window\\.LS\\s*=\\s*\\{[^}]{0,300}[\"']?id[\"']?\\s*:\\s*(\\d{5,})/" +
                "  ];" +
                "  for (var i = 0; i < scripts.length; i++) {" +
                "    var src = scripts[i].textContent || '';" +
                "    for (var j = 0; j < pats.length; j++) {" +
                "      var m = src.match(pats[j]);" +
                "      if (m && m[1]) return m[1];" +
                "    }" +
                "  }" +
                "  return null;" +
                "})()"
            );
        } catch (Exception e) { return null; }
    }

    private Optional<Product> fromApi(JsonNode prod, String dom) {
        try {
            String nombre = prod.path("name").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();

            String url = dom + prod.path("canonical_url").asText("");

            String img = "";
            JsonNode imgs = prod.path("images");
            if (imgs.isArray() && !imgs.isEmpty()) {
                img = imgs.get(0).path("src").asText("");
            }
            // Fallback 1: variants[0].image.src (ElDon y TN con imágenes por variante)
            if (img.isBlank()) {
                JsonNode vv = prod.path("variants");
                if (vv.isArray() && !vv.isEmpty()) {
                    img = vv.get(0).path("image").path("src").asText("");
                }
            }
            // Fallback 2: featured_image
            if (img.isBlank()) {
                img = prod.path("featured_image").asText(
                      prod.path("main_image").asText(""));
            }
            if (img.startsWith("//")) img = "https:" + img;
            // Limpiar sufijos de miniatura que añade el CDN de TN
            img = img.replaceAll("-\\d+x\\d+\\.(jpe?g|png|webp)", ".$1");
            img = img.replaceAll("-thumb\\.(jpe?g|png|webp)", ".$1");

            JsonNode variants = prod.path("variants");
            if (!variants.isArray() || variants.isEmpty()) return Optional.empty();
            JsonNode v = variants.get(0);

            Optional<Double> precio = parsePrecio(v.path("price").asText(""));
            if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax) return Optional.empty();

            String compare = v.path("compare_at_price").asText("");
            if ("null".equals(compare)) compare = "";

            // --- Categoría: categories[0].name o tags ---
            String categoria = "";
            JsonNode cats = prod.path("categories");
            if (cats.isArray() && !cats.isEmpty()) {
                categoria = cats.get(0).path("name").asText("").trim();
            }

            // --- Género: heurístico desde categorías, tags y nombre ---
            String genero = detectarGeneroApi(prod, nombre);

            // --- Talles: variants[].values[] donde attribute.es == "Talle" o similar ---
            List<String> talles = extraerTallesApi(prod, variants);

            return Optional.of(new Product(sitio, nombre, precio.get(),
                    compare.isBlank() ? null : compare,
                    url, img, categoria, genero, talles));
        } catch (Exception e) { return Optional.empty(); }
    }

    // ----------------------------------------------------------------
    // Extracción de talles — API Tiendanube
    // ----------------------------------------------------------------
    private List<String> extraerTallesApi(JsonNode prod, JsonNode variants) {
        // Estrategia 1: attributes del producto (Tiendanube los llama "attributes")
        JsonNode attrs = prod.path("attributes");
        if (attrs.isArray()) {
            for (JsonNode attr : attrs) {
                String attrName = attr.path("name").asText("").toLowerCase();
                if (esTalleAttr(attrName)) {
                    JsonNode vals = attr.path("values");
                    if (vals.isArray() && !vals.isEmpty()) {
                        List<String> talles = new ArrayList<>();
                        for (JsonNode val : vals) {
                            String t = val.asText("").trim();
                            if (!t.isBlank()) talles.add(t);
                        }
                        if (!talles.isEmpty()) return talles;
                    }
                }
            }
        }

        // Estrategia 2: variants[].values[] — cada variant tiene un array de values
        // Estructura: [{name: "Talle", value: "M"}, ...]
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode var : variants) {
            JsonNode values = var.path("values");
            if (values.isArray()) {
                for (JsonNode val : values) {
                    String attrName = val.path("name").asText("").toLowerCase();
                    if (esTalleAttr(attrName)) {
                        String t = val.path("value").asText("").trim();
                        if (!t.isBlank()) seen.add(t);
                    }
                }
            }
        }
        if (!seen.isEmpty()) return new ArrayList<>(seen);

        // Estrategia 3: fallback — primer valor de la primera variante visible
        for (JsonNode var : variants) {
            JsonNode values = var.path("values");
            if (values.isArray() && !values.isEmpty()) {
                String firstVal = values.get(0).path("value").asText("").trim();
                if (!firstVal.isBlank() && !firstVal.equalsIgnoreCase("unique")) {
                    // Recolectar todos los primeros valores únicos
                    for (JsonNode v2 : variants) {
                        String t = v2.path("values").isArray() && v2.path("values").size() > 0
                                ? v2.path("values").get(0).path("value").asText("").trim()
                                : "";
                        if (!t.isBlank()) seen.add(t);
                    }
                    return new ArrayList<>(seen);
                }
                break;
            }
        }

        return List.of();
    }

    private boolean esTalleAttr(String name) {
        return name.contains("talle") || name.contains("size") || name.contains("talla")
                || name.equals("tamaño") || name.contains("medida");
    }

    // ----------------------------------------------------------------
    // Detección de género — API Tiendanube
    // ----------------------------------------------------------------
    private String detectarGeneroApi(JsonNode prod, String nombre) {
        List<String> fuentes = new ArrayList<>();
        fuentes.add(nombre.toLowerCase());

        JsonNode cats = prod.path("categories");
        if (cats.isArray()) {
            for (JsonNode c : cats) fuentes.add(c.path("name").asText("").toLowerCase());
        }
        JsonNode tags = prod.path("tags");
        if (tags.isTextual()) {
            Arrays.stream(tags.asText("").split(","))
                    .map(String::trim).map(String::toLowerCase)
                    .forEach(fuentes::add);
        } else if (tags.isArray()) {
            for (JsonNode t : tags) fuentes.add(t.asText("").toLowerCase());
        }

        for (String f : fuentes) {
            if (PALABRAS_UNISEX.stream().anyMatch(f::contains)) return "unisex";
        }
        boolean esHombre = fuentes.stream().anyMatch(f ->
                PALABRAS_HOMBRE.stream().anyMatch(f::contains));
        boolean esMujer  = fuentes.stream().anyMatch(f ->
                PALABRAS_MUJER.stream().anyMatch(f::contains));

        if (esHombre && !esMujer)  return "hombre";
        if (esMujer  && !esHombre) return "mujer";
        if (esHombre && esMujer)   return "unisex";
        return "";
    }

    // ------------------------------------------------------------------
    // Estrategia 2: JS heurístico
    // ------------------------------------------------------------------
    private List<Product> scrapeJs() {
        List<Product> result = new ArrayList<>();
        String url = baseUrl;
        int pagina = 1;
        int paginasSinProductos = 0;

        while (url != null && pagina <= 25) {
            log.debug("[{}] JS p{} -> {}", sitio, pagina, url);
            try {
                navigateTo(url);
                scrollToBottom();
                page.waitForTimeout(1000);

                String json = (String) page.evaluate(buildExtractorJs());
                if (json == null || json.equals("[]") || json.equals("null")) {
                    log.debug("[{}] JS: 0 en p{}", sitio, pagina);
                    paginasSinProductos++;
                    if (paginasSinProductos >= 2) break; // 2 páginas vacías seguidas → fin
                } else {
                    paginasSinProductos = 0;
                    JsonNode arr = MAPPER.readTree(json);
                    log.debug("[{}] JS: {} en p{}", sitio, arr.size(), pagina);
                    for (JsonNode n : arr) fromJs(n).ifPresent(result::add);
                }
            } catch (Exception e) {
                // Error transitorio de Playwright (ej. TargetClosedError) en una
                // página intermedia NO debe descartar los productos ya acumulados
                // de páginas anteriores — se corta la paginación y se devuelve lo
                // recolectado hasta el momento.
                log.warn("[{}] JS error en p{}, se corta paginación conservando {} productos: {}",
                        sitio, pagina, result.size(), e.getMessage());
                break;
            }

            // Intentar encontrar la siguiente página
            String nextUrl = nextPageUrl(pagina);

            // Fallback: construir URL ?page=N si el DOM no tiene el link
            if (nextUrl == null && pagina < 25) {
                String candidata = urlPagina(baseUrl, pagina + 1);
                // Solo usar si es diferente a la actual (evitar loops)
                if (!candidata.equals(url)) {
                    nextUrl = candidata;
                    log.debug("[{}] paginacion por URL: {}", sitio, nextUrl);
                }
            }

            url = nextUrl;
            pagina++;
        }
        return result;
    }

    private Optional<Product> fromJs(JsonNode n) {
        try {
            String nombre = n.path("nombre").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();
            Optional<Double> precio = parsePrecio(n.path("precio").asText(""));
            if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax) return Optional.empty();
            String compare = n.path("compare").asText("").trim();
            String url     = absoluteUrl(n.path("url").asText(""), baseUrl);
            String img     = n.path("img").asText("").trim();
            if (img.startsWith("//")) img = "https:" + img;

            // Talles desde JS — array si se detectaron
            List<String> talles = new ArrayList<>();
            JsonNode jstalles = n.path("talles");
            if (jstalles.isArray()) {
                for (JsonNode t : jstalles) {
                    String tv = t.asText("").trim();
                    if (!tv.isBlank()) talles.add(tv);
                }
            }
            String genero = n.path("genero").asText("").trim();

            return Optional.of(new Product(sitio, nombre, precio.get(),
                    compare.isBlank() ? null : compare,
                    url, img, "", genero, talles));
        } catch (Exception e) { return Optional.empty(); }
    }

    private String buildExtractorJs() {
        return "(function() {" +
            "var results=[];" +
            "var seen=new Set();" +

            "var items=Array.from(document.querySelectorAll('[data-product-id]'));" +

            "if(items.length===0){" +
            "  items=Array.from(document.querySelectorAll('[data-item-id],li.js-product-item,.js-product-item,.ProductItem,.product-item'));" +
            "}" +

            "if(items.length===0){" +
            "  var paths=['/productos/','/product','-p-','/p/','/indumentaria/','/calzado/','/ropa/','/accesorios/','/coleccion/','/categoria/','/collections/'];" +
            "  var allLinks=Array.from(document.querySelectorAll('a[href]'));" +
            "  allLinks.forEach(function(a){" +
            "    var h=a.getAttribute('href')||'';" +
            "    var ok=paths.some(function(p){return h.includes(p);});" +
            "    if(!ok)return;" +
            "    var c=a.closest('[data-product-id]')||a.closest('li')||" +
            "           a.closest('article')||a.closest('.item')||" +
            "           a.closest('[class*=product]')||a.closest('[class*=card]')||" +
            "           a.parentElement;" +
            "    if(c&&c.tagName!=='BODY'&&c.tagName!=='HTML'&&!seen.has(c)){" +
            "      seen.add(c);items.push(c);" +
            "    }" +
            "  });" +
            "}" +

            "if(items.length===0){" +
            "  items=Array.from(document.querySelectorAll('[data-price]')).filter(function(el){" +
            "    return !el.querySelector('[data-price]');" +
            "  });" +
            "}" +

            "var palabrasH=['hombre','masculino','men','man','caballero'];" +
            "var palabrasM=['mujer','femenino','women','woman','dama'];" +

            "items.forEach(function(el){" +
            "  try{" +
            "    var nameEl=el.querySelector('h1,h2,h3,h4')||" +
            "               el.querySelector('[class*=name],[class*=title],[class*=nombre],[class*=tit]');" +
            "    var nombre=nameEl?nameEl.textContent.trim():'';" +
            "    if(!nombre){" +
            "      var a0=el.querySelector('a[title]')||el.querySelector('a');" +
            "      if(a0)nombre=a0.getAttribute('title')||a0.textContent.trim();" +
            "    }" +
            "    if(!nombre||nombre.length<2||nombre.length>200)return;" +

            "    var precio='';" +
            "    var dp=el.getAttribute('data-price');" +
            "    if(!dp){var dpEl=el.querySelector('[data-price]');if(dpEl)dp=dpEl.getAttribute('data-price');}" +
            "    var dpVal=dp?parseInt(dp):0;" +
            "    if(dpVal>0){precio='$'+dpVal;}" +

            "    if(!precio){" +
            "      var allEls=Array.from(el.querySelectorAll('*'));" +
            "      for(var j=0;j<allEls.length;j++){" +
            "        var s=allEls[j];" +
            "        if(s.children.length>0)continue;" +
            "        var t=s.textContent.trim();" +
            "        if(t.charAt(0)==='$'&&t.length>1&&t.length<20){" +
            "          var cleaned=t.replace('$','').split('.').join('').replace(',','.').trim();" +
            "          var pval=parseFloat(cleaned);" +
            "          if(pval>0&&!precio){precio=t;break;}" +
            "        }" +
            "      }" +
            "    }" +

            "    if(!precio){" +
            "      var priceEls=Array.from(el.querySelectorAll('[class*=price],[class*=precio],[class*=cost],[itemprop=price]'));" +
            "      for(var pi=0;pi<priceEls.length;pi++){" +
            "        var pt=priceEls[pi].getAttribute('content')||priceEls[pi].getAttribute('data-price')||'';" +
            "        if(!pt){" +
            "          var rawTxt=priceEls[pi].textContent;" +
            "          var idx=rawTxt.indexOf('$');" +
            "          if(idx>=0){var end=idx+1;while(end<rawTxt.length&&'0123456789.,'.indexOf(rawTxt[end])>=0)end++;if(end>idx+1)pt=rawTxt.substring(idx,end);}" +
            "        }" +
            "        if(pt){" +
            "          var cleanedP=pt.replace('$','').split('.').join('').replace(',','.').trim();" +
            "          if(parseFloat(cleanedP)>0){precio=pt.startsWith('$')?pt:('$'+pt);break;}" +
            "        }" +
            "      }" +
            "    }" +

            "    if(!precio)return;" +

            "    var cmpEl=el.querySelector('[class*=compare],[class*=before],[class*=original],[class*=tachado],[class*=crossed]');" +
            "    var compare=cmpEl?cmpEl.textContent.trim():'';" +

            "    var h2='';" +
            "    var allA=Array.from(el.querySelectorAll('a[href]'));" +
            "    for(var ai=0;ai<allA.length;ai++){" +
            "      var ah=allA[ai].getAttribute('href')||'';" +
            "      if(ah&&!ah.includes('javascript')&&ah!=='#'){h2=ah;break;}" +
            "    }" +

            "    var img='';" +
            "    var imgEl=el.querySelector('img[data-src],img[src]');" +
            "    if(!imgEl)imgEl=el.querySelector('img');" +
            "    if(imgEl){" +
            "      var iattrs=['data-zoom-src','data-srcset','data-src','data-lazy-src','data-original','src'];" +
            "      for(var k=0;k<iattrs.length;k++){" +
            "        var v=imgEl.getAttribute(iattrs[k])||'';" +
            "        if(v&&v.length>10&&v.indexOf('data:image/gif')<0&&v.indexOf('placeholder')<0&&v.indexOf('base64')<0){" +
            "          if(iattrs[k]==='data-srcset'||iattrs[k]==='srcset'){" +
            "            var parts=v.split(',').map(function(x){return x.trim().split(' ')[0];});" +
            "            v=parts[parts.length-1]||'';" +
            "          }" +
            "          if(v&&v.length>10){img=v;break;}" +
            "        }" +
            "      }" +
            "    }" +
            "    if(!img){" +
            "      var src=el.querySelector('source[srcset],source[data-srcset]');" +
            "      if(src){var sv=src.getAttribute('srcset')||src.getAttribute('data-srcset')||'';" +
            "        if(sv){var sp=sv.split(',').map(function(x){return x.trim().split(' ')[0];});img=sp[sp.length-1]||'';}}" +
            "    }" +

            "    var talles=[];" +
            "    var talleEls=el.querySelectorAll('[class*=size],[class*=talle],[data-size],[data-talle]');" +
            "    talleEls.forEach(function(te){var tv=te.textContent.trim();if(tv&&tv.length<=10&&!talles.includes(tv))talles.push(tv);});" +

            "    var cardText=(nombre+' '+(el.textContent||'')).toLowerCase();" +
            "    var genero='';" +
            "    var esH=palabrasH.some(function(p){return cardText.includes(p);});" +
            "    var esM=palabrasM.some(function(p){return cardText.includes(p);});" +
            "    if(esH&&!esM)genero='hombre';" +
            "    else if(esM&&!esH)genero='mujer';" +
            "    else if(esH&&esM)genero='unisex';" +

            "    if(!seen.has(el)){seen.add(el);results.push({nombre:nombre,precio:precio,compare:compare,url:h2,img:img,talles:talles,genero:genero});}" +
            "  }catch(e){}" +
            "});" +
            "return JSON.stringify(results);" +
            "})()";
    }
    /**
     * Pure static helper — extracts the max page number from a list of rendered
     * hrefs and returns maxN+1 iff maxN > currentPage and maxN < 1000.
     * No browser dependency; fully unit-testable.
     */
    public static OptionalInt resolveNextPageFromHrefs(List<String> hrefs, int currentPage) {
        var pat = java.util.regex.Pattern.compile("[?&]page=(\\d+)");
        int maxN = -1;
        for (String h : hrefs) {
            if (h == null) continue;
            java.util.regex.Matcher m = pat.matcher(h);
            while (m.find()) maxN = Math.max(maxN, Integer.parseInt(m.group(1)));
        }
        return (maxN > currentPage && maxN < 1000) ? OptionalInt.of(maxN + 1) : OptionalInt.empty();
    }

    private String nextPageUrl(int currentPage) {
        // Prioridad 1: <link rel="next"> en el head — TN lo incluye para SEO
        try {
            String headNext = (String) page.evaluate(
                "var l=document.querySelector('link[rel=next]');l?l.getAttribute('href'):null");
            if (headNext != null && !headNext.isBlank())
                return absoluteUrl(headNext, baseUrl);
        } catch (Exception ignored) {}

        // Prioridad 2: selectores DOM de paginacion
        String[] sels = {
            "a[rel='next']",
            "a[aria-label='Next']", "a[aria-label='Siguiente']",
            ".js-pagination-next", "a.next-page",
            "[class*=pagination-next]", "[class*=next-page]",
            "[class*=pagination] a[href*='page']",
            "a[href*='page=']"
        };
        for (String sel : sels) {
            try {
                var el = page.querySelector(sel);
                if (el != null) {
                    String href = safeAttr(el, "href");
                    if (!href.isBlank()) return absoluteUrl(href, baseUrl);
                }
            } catch (Exception ignored) {}
        }

        // Prioridad 3: escanear hrefs renderizados → buscar max page en el DOM
        try {
            Object raw = page.evaluate("() => Array.from(document.querySelectorAll('a[href]'))" +
                    ".map(a => a.getAttribute('href')).filter(h => h && h.includes('page='))");
            if (raw instanceof List<?> list) {
                List<String> hrefs = list.stream().map(String::valueOf).toList();
                var next = resolveNextPageFromHrefs(hrefs, currentPage);
                if (next.isPresent()) return urlPagina(baseUrl, next.getAsInt());
            }
        } catch (Exception ignored) {}

        // Prioridad 4 (hint, last resort): window.dataLayer
        try {
            String dl = (String) page.evaluate(
                    "() => { try { return JSON.stringify(window.dataLayer); } catch(e){ return null; } }");
            if (dl != null) {
                JsonNode arr = MAPPER.readTree(dl);
                for (JsonNode node : arr) {
                    JsonNode pg = node.has("page") ? node.get("page") : node.get("currentPage");
                    if (pg != null && pg.isInt() && pg.asInt() > currentPage)
                        return urlPagina(baseUrl, currentPage + 1);
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Construye la URL de la pagina N a partir de la URL base.
     * Soporta ?page=N y /p/N y /page/N
     */
    private String urlPagina(String base, int n) {
        if (n <= 1) return base;
        String b = base.replaceAll("[?&]page=[0-9]+", "")
                       .replaceAll("/page/[0-9]+", "")
                       .replaceAll("/p/[0-9]+$", "");
        // Preferir ?page= para TN
        String sep = b.contains("?") ? "&" : "?";
        return b + sep + "page=" + n;
    }
}
