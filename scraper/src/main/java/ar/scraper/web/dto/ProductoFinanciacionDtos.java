package ar.scraper.web.dto;

import java.util.List;

/** {@code GET /api/producto/{key}/financiacion}. Amounts are ARS; {@code consultadoAt} is UTC ISO-8601. */
public final class ProductoFinanciacionDtos {

    private ProductoFinanciacionDtos() {}

    public record Plan(int cuotas, double montoCuota, double total, boolean aproximado, String medio,
                       boolean sinInteres) { }

    public record Respuesta(String key, String url, String sitio, String consultadoAt, Double precioContado,
                            Double precioLista, String stockWeb, String stockLocal, List<Plan> planes) { }
}
