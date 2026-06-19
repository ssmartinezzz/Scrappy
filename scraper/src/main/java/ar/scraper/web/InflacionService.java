package ar.scraper.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Obtiene IPC mensual de Argentina desde fuentes públicas.
 * Fuente 1: argentinadatos.com  (REST simple)
 * Fuente 2: apis.datos.gob.ar   (INDEC oficial, serie 148.3_INIVELGENERAL_DICI_M_26)
 * Fallback: valores hardcoded si ambas fallan.
 */
@Service
public class InflacionService {

    private static final Logger LOG = LoggerFactory.getLogger(InflacionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Fuente 1 — argentinadatos.com  (devuelve [{fecha, valor}] orden asc)
    private static final String URL1 =
        "https://api.argentinadatos.com/v1/finanzas/indices/inflacion";

    // Fuente 2 — datos.gob.ar  (devuelve {"data": [[fecha, valor], ...]} orden asc)
    private static final String URL2 =
        "https://apis.datos.gob.ar/series/api/series/" +
        "?ids=148.3_INIVELGENERAL_DICI_M_26&format=json&limit=24";

    public record DatoIPC(String fecha, double valor, double variacionMensual) {}

    private final List<DatoIPC> historial = new ArrayList<>();
    private volatile double inflacionMensual  = 3.5;
    private volatile double inflacionInteranual = 150.0;
    private volatile double inflacion3m       = 11.0;
    private volatile String ultimaActualizacion = "sin datos";

    @PostConstruct
    public void init() { Thread.ofVirtual().start(this::fetch); }

    @Scheduled(cron = "0 0 8 * * *")
    public void fetchScheduled() { Thread.ofVirtual().start(this::fetch); }

    private void fetch() {
        if (fetchFuente1()) return;
        if (fetchFuente2()) return;
        LOG.warn("[IPC] Ambas fuentes fallaron — usando valores fallback");
    }

    // ── Fuente 1: argentinadatos.com ─────────────────────────────────────────
    private boolean fetchFuente1() {
        try {
            String body = get(URL1);
            JsonNode arr = MAPPER.readTree(body);
            if (!arr.isArray() || arr.size() < 2) return false;

            List<DatoIPC> datos = new ArrayList<>();
            JsonNode prev = null;
            for (JsonNode n : arr) {
                String fecha = n.path("fecha").asText();
                double valor = n.path("valor").asDouble();
                double var   = prev != null
                    ? prev.path("valor").asDouble() > 0
                        ? (valor - prev.path("valor").asDouble()) / prev.path("valor").asDouble() * 100.0
                        : 0.0
                    : 0.0;
                datos.add(new DatoIPC(fecha, valor, var));
                prev = n;
            }
            procesarDatos(datos);
            LOG.info("[IPC] Fuente 1 OK — mensual {}%  interanual {}%",
                     String.format("%.1f", inflacionMensual),
                     String.format("%.1f", inflacionInteranual));
            return true;
        } catch (Exception e) {
            LOG.debug("[IPC] Fuente 1 falló: {}", e.getMessage());
            return false;
        }
    }

    // ── Fuente 2: apis.datos.gob.ar ──────────────────────────────────────────
    private boolean fetchFuente2() {
        try {
            String body = get(URL2);
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() < 2) return false;

            List<DatoIPC> datos = new ArrayList<>();
            JsonNode prev = null;
            for (JsonNode row : data) {
                String fecha = row.get(0).asText();
                double valor = row.get(1).asDouble();
                double var   = prev != null && prev.get(1).asDouble() > 0
                    ? (valor - prev.get(1).asDouble()) / prev.get(1).asDouble() * 100.0
                    : 0.0;
                datos.add(new DatoIPC(fecha, valor, var));
                prev = row;
            }
            procesarDatos(datos);
            LOG.info("[IPC] Fuente 2 OK — mensual {}%  interanual {}%",
                     String.format("%.1f", inflacionMensual),
                     String.format("%.1f", inflacionInteranual));
            return true;
        } catch (Exception e) {
            LOG.debug("[IPC] Fuente 2 falló: {}", e.getMessage());
            return false;
        }
    }

    private void procesarDatos(List<DatoIPC> datos) {
        // datos vienen en orden ASC — el último es el más reciente
        synchronized (historial) {
            historial.clear();
            historial.addAll(datos);
        }
        if (datos.size() >= 1) {
            inflacionMensual = datos.get(datos.size() - 1).variacionMensual();
        }
        if (datos.size() >= 12) {
            double v0  = datos.get(datos.size()-1).valor();
            double v12 = datos.get(datos.size()-13).valor();
            if (v12 > 0) inflacionInteranual = (v0 - v12) / v12 * 100.0;
        }
        if (datos.size() >= 3) {
            double v0 = datos.get(datos.size()-1).valor();
            double v3 = datos.get(datos.size()-4).valor();
            if (v3 > 0) inflacion3m = (v0 - v3) / v3 * 100.0;
        }
        ultimaActualizacion = LocalDate.now().toString();
    }

    private String get(String url) throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
        var req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "FashionScraper/1.0")
            .header("Accept", "application/json")
            .GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public double getInflacionMensual()    { return inflacionMensual; }
    public double getInflacionInteranual() { return inflacionInteranual; }
    public double getInflacion3m()         { return inflacion3m; }
    public String getUltimaActualizacion() { return ultimaActualizacion; }

    public synchronized List<DatoIPC> getHistorial() {
        return Collections.unmodifiableList(historial);
    }

    /**
     * Ajusta un precio histórico al valor de hoy aplicando inflación acumulada.
     * Si no hay datos usa la inflación mensual promedio.
     */
    public double ajustarPorInflacion(double precioHistorico, int mesesAtras) {
        return precioHistorico * factorInflacion(mesesAtras);
    }

    /**
     * Factor multiplicativo de ajuste por inflación para los últimos
     * {@code mesesAtras} meses ({@code precioAjustado = precioHistorico * factor}).
     * Extraído de {@link #ajustarPorInflacion} para que callers que necesitan solo
     * el factor (p.ej. {@code SenalEnricher}, que delega la clasificación a la
     * función pura {@code SenalCalculator.compute}) no dependan de un precio
     * concreto.
     */
    public double factorInflacion(int mesesAtras) {
        synchronized (historial) {
            if (historial.size() >= mesesAtras + 1 && mesesAtras > 0) {
                int last = historial.size() - 1;
                double vHoy      = historial.get(last).valor();
                double vEntonces = historial.get(Math.max(0, last - mesesAtras)).valor();
                if (vEntonces > 0) return vHoy / vEntonces;
            }
        }
        return Math.pow(1.0 + inflacionMensual / 100.0, mesesAtras);
    }
}
