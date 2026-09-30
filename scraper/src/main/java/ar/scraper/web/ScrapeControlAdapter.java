package ar.scraper.web;

import ar.scraper.config.ScraperConfig;
import ar.scraper.ml.PythonRunner;
import ar.scraper.scrape.ScrapeControlPort;
import ar.scraper.scrape.ScraperStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * No es un bean mas que envuelve a {@code ScraperService} por prolijidad: el flag de GPU no vive en
 * {@code ScraperService} — vive en {@code PythonRunner} — y la banda de precio vive en
 * {@code ScraperConfig}.
 */
@Component
class ScrapeControlAdapter implements ScrapeControlPort {

    private final ScraperService scraperService;
    private final ScraperConfig config;
    private final PythonRunner pythonRunner;

    ScrapeControlAdapter(ScraperService scraperService, ScraperConfig config,
            PythonRunner pythonRunner) {
        this.scraperService = scraperService;
        this.config = config;
        this.pythonRunner = pythonRunner;
    }

    @Override
    public ScraperStatus estado() {
        return scraperService.getStatus();
    }

    @Override
    public boolean iniciar(Set<String> sitiosSeleccionados, boolean forceRetrain) {
        return scraperService.iniciarScraping(sitiosSeleccionados, forceRetrain);
    }

    @Override
    public double precioMinimo() {
        return config.getPrecioMinimo();
    }

    @Override
    public double precioMaximo() {
        return config.getPrecioMaximo();
    }

    @Override
    public void aplicarBandaDePrecio(double minimo, double maximo) {
        config.setPrecioMinimo(minimo);
        config.setPrecioMaximo(maximo);
    }

    @Override
    public void usarGpu(boolean usar) {
        pythonRunner.setUseGpu(usar);
    }
}
