package ar.scraper.pcs;

import java.util.Optional;
import java.util.UUID;

public interface PreferenciaArmadorPort {

    Optional<PreferenciaArmador> cargar(UUID usuarioId);

    void guardar(UUID usuarioId, PreferenciaArmador preferencia);
}
