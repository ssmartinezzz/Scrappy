package ar.scraper.favoritos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence port for the favoritos aggregate. Every operation is scoped to the owning
 *  user: there is deliberately no unscoped variant (see FavoritosRepository). */
public interface FavoritosPort {

    void guardarFavorito(UUID usuarioId, String url, String sitio, String nombre);

    void eliminarFavorito(UUID usuarioId, String url);

    List<Map<String, String>> listarFavoritos(UUID usuarioId);

    void tocarFavorito(UUID usuarioId, String url);
}
