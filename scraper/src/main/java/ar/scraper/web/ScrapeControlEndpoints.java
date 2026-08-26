package ar.scraper.web;

import ar.scraper.config.ScraperConfig;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run control and configuration: scrape status/progress, launching a run, the
 * site registry and the price-range config.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class ScrapeControlEndpoints {

    private final ScraperService service;
    private final ScraperConfig config;

    ScrapeControlEndpoints(ScraperService service, ScraperConfig config) {
        this.service = service;
        this.config = config;
    }

    // ---------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------
    ResponseEntity<ObjectNode> status() {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        b.put("status",    service.getStatus().name());
        b.put("mensaje",   service.getStatusMsg());
        var lr = service.getLastResult();
        b.put("tieneData", lr != null);
        if (lr != null) {
            b.put("total", lr.productos().size());
            // Stats ML del último scraping
            b.put("mlRefinadas", service.getUltimasCategoriasRefinadas());
            b.put("mlModeloActivo", new java.io.File("_models/text_classifier.pkl").exists());
            // Extraction quality stats — additive, does not change existing keys
            var st = lr.statsPorSitio();
            if (st != null && !st.isEmpty()) {
                ObjectNode sNode = b.putObject("extractionStats");
                st.forEach((sitio, s) -> {
                    ObjectNode sn = sNode.putObject(sitio);
                    sn.put("total",  s.total());
                    sn.put("valid",  s.valid());
                    sn.put("misses", s.misses());
                });
            }
        }

        // El run persistido (V29), ADITIVO: `status` sigue siendo IDLE|RUNNING|
        // DONE|ERROR y una corrida cancelada reporta DONE. Meter CANCELLED en el
        // enum cambiaría la superficie del contrato del CLI (`cli/core/rest.py`)
        // sin ganar nada funcional; un campo nuevo es invisible para los
        // consumidores viejos y le da `run.status` al que lo quiera.
        var rs = service.getRunState();
        if (rs != null) {
            ObjectNode run = b.putObject("run");
            run.put("uuid",      rs.scrapeUuid().toString());
            run.put("startedAt", rs.startedAt().toString());
            run.put("cancelando", service.estaCancelado());
        }

        // Progreso en tiempo real
        ScraperService.ProgressData pd = service.getProgressData();
        if (pd != null) {
            ObjectNode prog = b.putObject("progreso");
            prog.put("total",       pd.total());
            prog.put("completados", pd.completados());
            prog.put("productos",   pd.productosAcumulados());
            ArrayNode sitiosArr = prog.putArray("sitios");
            for (var sp : pd.sitios()) {
                ObjectNode sn = sitiosArr.addObject();
                sn.put("nombre",  sp.nombre());
                sn.put("estado",  sp.estado().name().toLowerCase());
                sn.put("count",   sp.productos());
                sn.put("durMs",   sp.duracionMs());
                if (sp.error() != null && !sp.error().isBlank())
                    sn.put("error", sp.error().length() > 60
                            ? sp.error().substring(0, 60) + "..." : sp.error());
            }
        }
        return ResponseEntity.ok(b);
    }

    // ---------------------------------------------------------------
    // Corrida interrumpida: ofrecerla y retomarla
    // ---------------------------------------------------------------

    /**
     * Qué dejó abierto el proceso anterior. Sólo informa: detectar no reanuda.
     */
    ResponseEntity<ObjectNode> interrumpida() {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        var det = service.getInterrumpida();
        b.put("hayInterrumpida", det != null);
        if (det != null) {
            b.put("uuid",      det.uuid().toString());
            b.put("startedAt", det.startedAt().toString());
            b.put("soloFaltaLaPasadaFinal", det.soloFaltaLaPasadaFinal());
            ArrayNode at = b.putArray("atendidos");
            det.atendidos().forEach(at::add);
            ArrayNode pe = b.putArray("pendientes");
            det.pendientes().forEach(pe::add);
            // Los salteados se nombran a propósito: un sitio que salió del
            // registro entre la caída y el reinicio no se puede retomar, y que
            // desaparezca en silencio de una corrida que lo debía es peor que
            // no retomarlo.
            ArrayNode sk = b.putArray("salteados");
            det.salteados().forEach(sk::add);
        }
        return ResponseEntity.ok(b);
    }

    ResponseEntity<ObjectNode> retomar() {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        boolean ok = service.reanudar();
        b.put("retomando", ok);
        b.put("mensaje", ok
                ? "Retomando la corrida interrumpida"
                : "No hay corrida interrumpida, o ya hay un scraping en curso");
        return ResponseEntity.ok(b);
    }

    // ---------------------------------------------------------------
    // Cancelar
    // ---------------------------------------------------------------
    ResponseEntity<ObjectNode> cancelar() {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        boolean ok = service.cancelar();
        b.put("cancelando", ok);
        b.put("mensaje", ok
                ? "Cancelando: se deja de esperar sitios y el catálogo queda como está"
                : "No hay ningún scraping en curso");
        return ResponseEntity.ok(b);
    }

    // ---------------------------------------------------------------
    // Lanzar scraping
    // ---------------------------------------------------------------
    ResponseEntity<ObjectNode> scrape(Double precioMin, Double precioMax, Double precio,
                                      List<String> sitios, boolean forceRetrain) {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        if (precioMin != null) config.setPrecioMinimo(precioMin);
        if (precioMax != null) config.setPrecioMaximo(precioMax);
        if (precio    != null) config.setPrecioMaximo(precio);

        Set<String> seleccion = (sitios != null && !sitios.isEmpty())
                ? new HashSet<>(sitios) : null;

        boolean ok = service.iniciarScraping(seleccion, forceRetrain);
        b.put("iniciado", ok);
        b.put("mensaje", ok ? "Scraping iniciado" : "Ya hay un scraping en curso");
        return ResponseEntity.ok(b);
    }

    // ---------------------------------------------------------------
    // Gestión de sitios
    // ---------------------------------------------------------------
    ResponseEntity<ObjectNode> getSitios() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode base = root.putArray("base");
        for (var s : config.getSitiosActivos()) {
            ObjectNode n = base.addObject();
            n.put("nombre", s.nombre());
            n.put("url", s.url());
            n.put("tipo", "config");
            n.put("rubro", s.rubro());
        }
        ArrayNode extras = root.putArray("extras");
        for (var s : service.getSitiosExtras()) {
            ObjectNode n = extras.addObject();
            n.put("nombre", s.nombre());
            n.put("url", s.url());
            n.put("plataforma", s.plataforma());
            n.put("tipo", "dinamico");
        }
        root.put("precioMinimo", config.getPrecioMinimo());
        root.put("precioMaximo", config.getPrecioMaximo());
        root.put("moneda", config.getMoneda());
        return ResponseEntity.ok(root);
    }

    ResponseEntity<ObjectNode> agregarSitio(Map<String, String> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String nombre     = body.getOrDefault("nombre", "").trim();
        String url        = body.getOrDefault("url", "").trim();
        String plataforma = body.getOrDefault("plataforma", "tiendanube").trim();
        if (nombre.isBlank() || url.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "nombre y url son obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }
        if (!url.startsWith("http")) url = "https://" + url;
        service.agregarSitio(nombre, url, plataforma);
        resp.put("ok", true);
        resp.put("mensaje", "Sitio '" + nombre + "' agregado. Corré el scraper para incluirlo.");
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> eliminarSitio(String nombre) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        boolean ok = service.eliminarSitio(nombre);
        resp.put("ok", ok);
        resp.put("mensaje", ok ? "Sitio eliminado" : "Sitio no encontrado");
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> updateConfig(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        if (body.containsKey("precioMinimo")) {
            double v = Double.parseDouble(body.get("precioMinimo").toString());
            config.setPrecioMinimo(v);
            resp.put("precioMinimo", v);
        }
        if (body.containsKey("precioMaximo")) {
            double v = Double.parseDouble(body.get("precioMaximo").toString());
            config.setPrecioMaximo(v);
            resp.put("precioMaximo", v);
        }
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }
}
