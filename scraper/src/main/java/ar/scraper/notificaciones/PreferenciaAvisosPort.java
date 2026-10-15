package ar.scraper.notificaciones;

import java.util.Optional;
import java.util.UUID;

/** Per-account opt-out for price-drop mail. Scoped to the owner: there is no unscoped variant. */
public interface PreferenciaAvisosPort {

    Optional<PreferenciaAvisos> leer(UUID usuarioId);

    /** Returns false, changing nothing, when the account has no email address. */
    boolean actualizar(UUID usuarioId, boolean notificarBajasPrecio);
}
