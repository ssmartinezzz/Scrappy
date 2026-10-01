package ar.scraper.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;

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

    /** Techo de precio, GLOBAL para los 24 sitios — no hay override por sitio. */
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

    /** Opcional: casi ningún sitio lo define y el {@code fallback} alcanza. */
    public int getMaxPaginas(String nombreSitio, int fallback) {
        String key = (nombreSitio != null ? nombreSitio : "").toLowerCase();
        String raw = props.getProperty("sitio." + key + ".max_paginas");
        if (StringUtils.isBlank(raw)) return fallback;
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

    private List<String> parseExtraUrls(String nombre) {
        String raw = props.getProperty("sitio." + nombre + ".urls_extra", "");
        if (StringUtils.isBlank(raw)) return List.of();
        List<String> urls = new ArrayList<>();
        for (String u : raw.split(",")) {
            String t = u.trim();
            if (!t.isEmpty()) urls.add(t);
        }
        return urls;
    }

    public record SiteConfig(String nombre, String url, String rubro, List<String> extraUrls) {
        public SiteConfig(String nombre, String url, String rubro) {
            this(nombre, url, rubro, List.of());
        }
    }
}
