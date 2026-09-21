package ar.scraper.pcs;

import ar.scraper.pcs.reglas.ReglaCompatibilidad;

import java.util.List;

/** One build slot: which catalog category it draws from and the rules its candidates must pass. */
public record SlotDeArmado(String nombre, String categoria, List<ReglaCompatibilidad> reglas) {
}
