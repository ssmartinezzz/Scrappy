package ar.scraper.indices;

/** `tecnologia` prices move with the dollar, not the CPI basket. */
public final class DeflactorPorRubro {

    private DeflactorPorRubro() {
    }

    public static Indice resolver(String rubro) {
        return "tecnologia".equals(rubro) ? Indice.USD_OFICIAL : Indice.IPC;
    }
}
