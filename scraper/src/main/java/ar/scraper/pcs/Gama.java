package ar.scraper.pcs;

/**
 * {@code DESCONOCIDA} is abstention ("the name didn't parse to a tier") and, unlike
 * {@link Certificacion}'s {@code NINGUNA}, is NOT part of that scale — it is neither below
 * {@code BAJA} nor above {@code ALTA}.
 */
public enum Gama {
    BAJA, MEDIA, ALTA, DESCONOCIDA;

    public boolean esConocida() {
        return this != DESCONOCIDA;
    }
}
