package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.OpResult;
import org.springframework.http.HttpStatus;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import ar.scraper.catalog.ProductJson;
import ar.scraper.catalog.ProductPort;
import ar.scraper.favoritos.FavoritosPort;
import ar.scraper.identity.ActorResolver;
import ar.scraper.identity.Sujeto;

import java.util.Map;

/** Saved products ("favoritos"). Mappings live in {@link ApiController}. */
class FavoritosEndpoints {

    private final FavoritosPort favoritos;
    private final ProductPort productos;
    private final ActorResolver actorResolver;

    FavoritosEndpoints(FavoritosPort favoritos, ProductPort productos,
                       ActorResolver actorResolver) {
        this.favoritos = favoritos;
        this.productos = productos;
        this.actorResolver = actorResolver;
    }

    // Items are ProductJson rows (dynamic shape shared with /api/data), hence ObjectNode.
    ResponseEntity<ApiResponse<List<ObjectNode>>> getFavoritos() {
        List<ObjectNode> arr = new ArrayList<>();
        for (var f : favoritos.listarFavoritos(Sujeto.de(actorResolver))) {
            String url = f.get("url");
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            arr.add(n);
            // Same shape as /api/data so DetailPanel needs no extra request.
            productos.obtenerProducto(url).ifPresent(p -> ProductJson.escribir(n, p));
            n.put("url",    url);
            n.put("sitio",  ProductJson.safe(f.get("sitio")));
            n.put("nombre", n.has("nombre") && !n.get("nombre").asText().isBlank()
                    ? n.get("nombre").asText() : ProductJson.safe(f.get("nombre")));
            n.put("addedAt",       ProductJson.safe(f.get("added_at")));
            n.put("lastCheckedAt", ProductJson.safe(f.get("last_checked_at")));
            n.put("descontinuado", !productos.esProductoActivo(url));
        }
        return ResponseEntity.ok(ApiResponse.ok(arr));
    }

    ResponseEntity<ApiResponse<OpResult>> addFavorito(Map<String, String> body) {
        String url    = body.getOrDefault("url", "").trim();
        String sitio  = body.getOrDefault("sitio", "").trim();
        String nombre = body.getOrDefault("nombre", "").trim();
        if (url.isBlank() || sitio.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "url y sitio obligatorios");
        }
        favoritos.guardarFavorito(Sujeto.de(actorResolver), url, sitio, nombre);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    ResponseEntity<ApiResponse<OpResult>> deleteFavorito(String url) {
        favoritos.eliminarFavorito(Sujeto.de(actorResolver), url);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }
}
