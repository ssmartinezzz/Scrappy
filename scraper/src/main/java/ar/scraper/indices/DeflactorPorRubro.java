package ar.scraper.indices;

/**
 * Which index deflates a product's price, decided by what it tracks rather
 * than what inflates the peso in general: `tecnologia` prices move with the
 * dollar, not the CPI basket (D1).
 */
public final class DeflactorPorRubro {

    private DeflactorPorRubro() {
    }

    public static Indice resolver(String rubro) {
        return "tecnologia".equals(rubro) ? Indice.USD_OFICIAL : Indice.IPC;
    }
}
