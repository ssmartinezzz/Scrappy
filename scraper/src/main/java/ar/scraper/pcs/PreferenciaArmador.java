package ar.scraper.pcs;

/**
 * The builder settings a user last picked: {@code gama} requested, budget and
 * whether to include a GPU. {@code presupuesto} is nullable — the user may
 * never have set one. {@code gama} is never {@link Gama#DESCONOCIDA}: that
 * value is an abstention sentinel from name parsing (see {@link TechSpecs}),
 * not a tier anyone can request or save (D10, odd/tasks/pc-builder-gama.md).
 */
public record PreferenciaArmador(Gama gama, Double presupuesto, boolean conGpu) {
}
