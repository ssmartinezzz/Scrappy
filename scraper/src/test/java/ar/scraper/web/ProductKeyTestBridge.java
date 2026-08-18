package ar.scraper.web;

/**
 * {@link ProductKey} es package-private, y el test de paridad vive en
 * {@code ar.scraper.db} porque necesita {@code PostgresTestBase}. Este puente
 * expone el cálculo sin ampliar la visibilidad del helper de producción: si
 * {@code ProductKey} fuera público sólo para un test, la próxima persona lo
 * leería como parte de la API del paquete.
 */
public final class ProductKeyTestBridge {
    private ProductKeyTestBridge() {}
    public static String of(String url) { return ProductKey.of(url); }
}
