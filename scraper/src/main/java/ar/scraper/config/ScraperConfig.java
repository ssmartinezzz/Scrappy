package ar.scraper.config;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Component
public class ScraperConfig {

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

    public double getPrecioMaximo() {
        return Double.parseDouble(props.getProperty("precio.maximo", "300000"));
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
                    list.add(new SiteConfig(nombre, props.getProperty(key), rubro));
                }
            }
        }
        return list;
    }

    public record SiteConfig(String nombre, String url, String rubro) {}
}
