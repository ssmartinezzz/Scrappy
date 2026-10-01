package ar.scraper.web;

import ar.scraper.web.dto.ScrapeDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScrapeStatusView {

    private final ScraperService service;

    public ScrapeStatusView(ScraperService service) {
        this.service = service;
    }

    public ScrapeDtos.Status snapshot() {
        var b = ScrapeDtos.Status.builder()
                .status(service.getStatus().name())
                .mensaje(service.getStatusMsg());
        var lr = service.getLastResult();
        b.tieneData(lr != null);
        if (lr != null) {
            b.total(lr.productos().size());
            b.mlRefinadas(service.getUltimasCategoriasRefinadas());
            b.mlModeloActivo(new java.io.File("_models/text_classifier.pkl").exists());
            var st = lr.statsPorSitio();
            if (st != null && !st.isEmpty()) {
                Map<String, ScrapeDtos.ExtractionStats> stats = new LinkedHashMap<>();
                st.forEach((sitio, s) ->
                        stats.put(sitio, new ScrapeDtos.ExtractionStats(s.total(), s.valid(), s.misses())));
                b.extractionStats(stats);
            }
        }

        // `status` stays IDLE|RUNNING|DONE|ERROR (a cancelled run reports DONE) so the CLI contract
        // in cli/core/rest.py does not gain an enum value.
        var rs = service.getRunState();
        if (rs != null) {
            b.run(new ScrapeDtos.RunInfo(rs.scrapeUuid().toString(), rs.startedAt().toString(),
                    service.estaCancelado()));
        }

        ScraperService.ProgressData pd = service.getProgressData();
        if (pd != null) {
            List<ScrapeDtos.SitioProgreso> sitios = new ArrayList<>();
            for (var sp : pd.sitios()) {
                sitios.add(ScrapeDtos.SitioProgreso.desde(
                        sp.nombre(), sp.estado().name(), sp.productos(), sp.duracionMs(), sp.error()));
            }
            b.progreso(new ScrapeDtos.Progreso(pd.total(), pd.completados(),
                    pd.productosAcumulados(), sitios));
        }
        return b.build();
    }
}
