package ar.scraper.pcs;

import ar.scraper.pcs.reglas.ReglaCompatibilidad;

import java.util.List;

/**
 * One build slot: which catalog category it draws from, the rules its
 * candidates must pass, and the criterio that ranks the survivors
 * (pc-builder-gama T3b — one {@link CriterioDeSeleccion} per slot, replacing
 * the single global {@code CriterioScoreMlPrecioUrl}).
 */
public record SlotDeArmado(String nombre, String categoria, List<ReglaCompatibilidad> reglas,
        CriterioDeSeleccion criterio) {
}
