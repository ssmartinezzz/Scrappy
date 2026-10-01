package ar.scraper.pcs;

/** {@code presupuesto} is nullable — the user may never have set one. */
public record PreferenciaArmador(Gama gama, Double presupuesto, boolean conGpu, PreferenciasDeArmado preferencias,
        Uso uso) {

    public PreferenciaArmador(Gama gama, Double presupuesto, boolean conGpu, PreferenciasDeArmado preferencias) {
        this(gama, presupuesto, conGpu, preferencias, Uso.GAMING);
    }

    public PreferenciaArmador(Gama gama, Double presupuesto, boolean conGpu) {
        this(gama, presupuesto, conGpu, PreferenciasDeArmado.NINGUNA);
    }
}
