package ar.scraper.pcs;

/**
 * Tower size read off a Gabinete's name — {@code mini-tower} / {@code
 * mid-tower} / {@code full-tower}. {@code DESCONOCIDO} is abstention ("the
 * name didn't say") and, like {@link TipoCooler#DESCONOCIDO}, is NOT a rung
 * of the MINI &lt; MID &lt; FULL scale: {@link #esConocido()} models that
 * explicitly instead of leaning on the enum's declaration order.
 *
 * <p>This is a DIFFERENT axis from {@code TechSpecs.formFactor} (D1, fase 9):
 * the tower size is how much desk it takes, the form factor is which board
 * fits inside. The catalog names them separately ("MID-TOWER EATX" carries
 * both) and "mid-ATX" is not a thing. The Gabinete ⊇ Mother veto keeps
 * running on {@code formFactor} alone, untouched.</p>
 *
 * <p>Coverage measured on the dev DB (2026-09-22): 46 of 622 Gabinete rows
 * declare a tower size — MID 43, FULL 2, MINI 1. That is why, like every
 * other axis, abstention never vetoes unless the user explicitly asked for
 * a size (D2).</p>
 */
public enum TamanioGabinete {
    MINI, MID, FULL, DESCONOCIDO;

    public boolean esConocido() {
        return this != DESCONOCIDO;
    }
}
