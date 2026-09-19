package ar.scraper.pcs;

/**
 * Power tier read off a CPU/GPU name. {@code BAJA}/{@code MEDIA}/{@code ALTA}
 * form the requested scale; {@code DESCONOCIDA} is abstention ("the name
 * didn't parse to a tier") and, unlike {@link Certificacion}'s {@code
 * NINGUNA}, is NOT part of that scale — it is neither below {@code BAJA} nor
 * above {@code ALTA}. Modelled explicitly with {@link #esConocida()} instead
 * of relying on enum declaration order: Java's implicit ordinal would
 * otherwise place {@code DESCONOCIDA} above or below the other three
 * depending on where it's declared, which is not a claim this type makes.
 */
public enum Gama {
    BAJA, MEDIA, ALTA, DESCONOCIDA;

    public boolean esConocida() {
        return this != DESCONOCIDA;
    }
}
