package ar.scraper.web;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import ar.scraper.identity.ActorResolver;

import java.util.Map;

/**
 * Saved products ("favoritos").
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 *
 * <p>{@code DELETE /api/data} is deliberately NOT here. It is written inside the
 * favoritos region of the controller, but it soft-deletes a catalog product and
 * belongs with the catalog endpoints.</p>
 */
class FavoritosEndpoints {

    private final ar.scraper.db.DatabaseService db;
    private final ActorResolver actorResolver;

    FavoritosEndpoints(ar.scraper.db.DatabaseService db, ActorResolver actorResolver) {
        this.db = db;
        this.actorResolver = actorResolver;
    }

    ResponseEntity<ArrayNode> getFavoritos() {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (var f : db.listarFavoritos(Sujeto.de(actorResolver))) {
            String url = f.get("url");
            ObjectNode n = arr.addObject();
            // Si tenemos el producto en la DB, volcamos sus campos con la misma
            // forma que /api/data (precio, img, ml, etc.) para que DetailPanel
            // pueda mostrarlo sin pedir nada extra.
            db.obtenerProducto(url).ifPresent(p -> ProductJson.escribir(n, p));
            n.put("url",    url);
            n.put("sitio",  ProductJson.safe(f.get("sitio")));
            n.put("nombre", n.has("nombre") && !n.get("nombre").asText().isBlank()
                    ? n.get("nombre").asText() : ProductJson.safe(f.get("nombre")));
            n.put("addedAt",       ProductJson.safe(f.get("added_at")));
            n.put("lastCheckedAt", ProductJson.safe(f.get("last_checked_at")));
            n.put("descontinuado", !db.esProductoActivo(url));
        }
        return ResponseEntity.ok(arr);
    }

    ResponseEntity<ObjectNode> addFavorito(Map<String, String> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String url    = body.getOrDefault("url", "").trim();
        String sitio  = body.getOrDefault("sitio", "").trim();
        String nombre = body.getOrDefault("nombre", "").trim();
        if (url.isBlank() || sitio.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "url y sitio obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }
        db.guardarFavorito(Sujeto.de(actorResolver), url, sitio, nombre);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> deleteFavorito(String url) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        db.eliminarFavorito(Sujeto.de(actorResolver), url);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }
}
