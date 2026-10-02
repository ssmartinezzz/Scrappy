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
import org.springframework.web.bind.annotation.*;

import ar.scraper.json.ProductJson;
import ar.scraper.catalog.ProductPort;
import ar.scraper.favoritos.FavoritosPort;
import ar.scraper.security.ActorResolver;
import ar.scraper.security.Sujeto;

import java.util.Map;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FavoritosController {

    private final FavoritosPort favoritos;
    private final ProductPort productos;
    private final ActorResolver actorResolver;

    @GetMapping("/favoritos")
    public ResponseEntity<ApiResponse<List<ObjectNode>>> getFavoritos() {
        List<ObjectNode> arr = new ArrayList<>();
        for (var f : favoritos.listarFavoritos(Sujeto.de(actorResolver))) {
            String url = f.get("url");
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            arr.add(n);
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

    @PostMapping("/favoritos")
    public ResponseEntity<ApiResponse<OpResult>> addFavorito(@RequestBody Map<String, String> body) {
        String url    = body.getOrDefault("url", "").trim();
        String sitio  = body.getOrDefault("sitio", "").trim();
        String nombre = body.getOrDefault("nombre", "").trim();
        if (url.isBlank() || sitio.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "url y sitio obligatorios");
        }
        favoritos.guardarFavorito(Sujeto.de(actorResolver), url, sitio, nombre);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    @DeleteMapping("/favoritos")
    public ResponseEntity<ApiResponse<OpResult>> deleteFavorito(@RequestParam String url) {
        favoritos.eliminarFavorito(Sujeto.de(actorResolver), url);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }
}
