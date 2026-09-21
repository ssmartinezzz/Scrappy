package ar.scraper.pcs;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the one saved builder preference per user
 * ({@code preferencia_armador}, V35). Same molde as the other 13 ports in
 * this area: implemented by a package-private {@code @Repository} in
 * {@code ar.scraper.db}.
 */
public interface PreferenciaArmadorPort {

    Optional<PreferenciaArmador> cargar(UUID usuarioId);

    void guardar(UUID usuarioId, PreferenciaArmador preferencia);
}
