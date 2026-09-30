package ar.scraper.financiacion;

import java.util.List;
import java.util.Optional;

public interface PresetPort {

    void seedPresetIlustrativoSiVacio();

    List<Preset> listarPresets();

    Optional<Preset> cargarPresetActivo();

    int crearPreset(String label, double recargoPct, int cuotas);

    boolean editarPreset(int id, String label, double recargoPct, int cuotas);

    /** Activa el preset {@code id} y desactiva todos los demás, de forma transaccional. */
    boolean activarPreset(int id);

    boolean eliminarPreset(int id);
}
