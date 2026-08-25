package ar.scraper.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Component
public class ScraperConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ScraperConfig.class);

    private final Properties props = new Properties();

    public ScraperConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (is == null) throw new RuntimeException("No se encontro config.properties");
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Error cargando config.properties", e);
        }
    }

    ScraperConfig(Properties seed) {
        this.props.putAll(seed);
    }

    /**
     * Techo de precio, GLOBAL para los 24 sitios — no hay override por sitio.
     *
     * <p>El default tiene que decir lo mismo que {@code config.properties}: es
     * el valor que rige si el archivo llega truncado, y si discrepan la banda
     * cambia sin que nadie lo haya pedido.</p>
     *
     * <p>{@link #setPrecioMaximo} sólo toca el {@code Properties} en memoria —
     * {@code PUT /api/config} NO persiste, se pierde al reiniciar. El valor
     * durable es el del archivo.</p>
     */
    public double getPrecioMaximo() {
        return Double.parseDouble(props.getProperty("precio.maximo", "5000000"));
    }
    public void setPrecioMaximo(double v) {
        props.setProperty("precio.maximo", String.valueOf(v));
    }

    public double getPrecioMinimo() {
        return Double.parseDouble(props.getProperty("precio.minimo", "0"));
    }
    public void setPrecioMinimo(double v) {
        props.setProperty("precio.minimo", String.valueOf(v));
    }
    public String getMoneda()        { return props.getProperty("moneda", "ARS"); }
    public int getThreadsParalelos() { return Integer.parseInt(props.getProperty("threads.paralelos", "8")); }
    public int getTimeoutMs()        { return Integer.parseInt(props.getProperty("timeout.ms", "30000")); }
    public boolean isHeadless()      { return Boolean.parseBoolean(props.getProperty("headless", "true")); }

    public List<SiteConfig> getSitiosActivos() {
        List<SiteConfig> list = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("sitio.") && key.endsWith(".url")) {
                String nombre = key.replace("sitio.", "").replace(".url", "");
                if (Boolean.parseBoolean(props.getProperty("sitio." + nombre + ".activo", "true"))) {
                    String rubro = props.getProperty("sitio." + nombre + ".rubro", "indumentaria");
                    list.add(new SiteConfig(nombre, props.getProperty(key), rubro,
                            parseExtraUrls(nombre)));
                }
            }
        }
        return list;
    }

    /**
     * Tope de páginas para el sitio, desde {@code sitio.<nombre>.max_paginas}.
     * Opcional: casi ningún sitio lo define y el {@code fallback} alcanza.
     *
     * <p>El default NO vive acá a propósito. Lo dueña la página que consume el
     * tope ({@code TiendanubePage.MAX_PAGINAS_DEFAULT}) y el llamador lo pasa,
     * así {@code ar.scraper.config} no depende de {@code ar.scraper.pages} y
     * el número sigue teniendo UNA sola definición ({@code CODE-6}).
     *
     * <p>Un valor no numérico o {@code < 1} cae al fallback con un warning en
     * vez de romper: un typo en config.properties no debe abortar un scrape, y
     * un cap de 0 no traería ningún producto.
     *
     * @param nombreSitio nombre del sitio; se acepta el display name porque
     *                    {@code ScraperFactory} lo deriva capitalizando la
     *                    clave en minúscula, así que bajar a minúscula es exacto
     */
    public int getMaxPaginas(String nombreSitio, int fallback) {
        String key = (nombreSitio != null ? nombreSitio : "").toLowerCase();
        String raw = props.getProperty("sitio." + key + ".max_paginas");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                LOG.warn("sitio.{}.max_paginas={} no es >= 1, se usa el default {}", key, raw, fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOG.warn("sitio.{}.max_paginas={} no es un entero, se usa el default {}", key, raw, fallback);
            return fallback;
        }
    }

    /**
     * URLs adicionales a crawlear bajo el mismo sitio, desde
     * {@code sitio.<nombre>.urls_extra} (separadas por coma). Sirve para sumar
     * colecciones que el catálogo principal no cubre (ej. Harvey Willys
     * {@code /otras-temporadas1}). Vacío si la propiedad no existe.
     */
    private List<String> parseExtraUrls(String nombre) {
        String raw = props.getProperty("sitio." + nombre + ".urls_extra", "");
        if (raw == null || raw.isBlank()) return List.of();
        List<String> urls = new ArrayList<>();
        for (String u : raw.split(",")) {
            String t = u.trim();
            if (!t.isEmpty()) urls.add(t);
        }
        return urls;
    }

    public record SiteConfig(String nombre, String url, String rubro, List<String> extraUrls) {
        /** Constructor compacto sin colecciones extra (retrocompatibilidad). */
        public SiteConfig(String nombre, String url, String rubro) {
            this(nombre, url, rubro, List.of());
        }
    }
}
