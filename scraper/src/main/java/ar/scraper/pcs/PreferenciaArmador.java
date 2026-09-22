package ar.scraper.pcs;

/**
 * The builder settings a user last picked: {@code gama} requested, budget,
 * whether to include a GPU, and the technical preferences from
 * pc-builder-deep-taxonomy T5 (D7/D8). {@code presupuesto} is nullable — the
 * user may never have set one. {@code gama} is never {@link Gama#DESCONOCIDA}:
 * that value is an abstention sentinel from name parsing (see
 * {@link TechSpecs}), not a tier anyone can request or save (D10,
 * odd/tasks/pc-builder-gama.md). {@code preferencias} is never null —
 * {@link PreferenciasDeArmado#NINGUNA} when nothing was requested.
 */
public record PreferenciaArmador(Gama gama, Double presupuesto, boolean conGpu, PreferenciasDeArmado preferencias) {

    /**
     * Pre-{@code pc-builder-deep-taxonomy} shape (3 args, the record's
     * canonical constructor before T5 added {@code preferencias}): kept so
     * every existing caller/test keeps compiling untouched — refactor
     * contract, CODE-2. Defaults to {@link PreferenciasDeArmado#NINGUNA},
     * same as "nothing requested" everywhere else in this area.
     */
    public PreferenciaArmador(Gama gama, Double presupuesto, boolean conGpu) {
        this(gama, presupuesto, conGpu, PreferenciasDeArmado.NINGUNA);
    }
}
