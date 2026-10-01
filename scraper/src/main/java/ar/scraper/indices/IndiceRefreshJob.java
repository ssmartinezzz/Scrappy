package ar.scraper.indices;

public class IndiceRefreshJob {

    private final IndiceService service;

    public IndiceRefreshJob(IndiceService service) {
        this.service = service;
    }

    public void alArrancar() {
        service.cargarDesdeDB();
        Thread.ofVirtual().start(service::refrescar);
    }

    public void diario() {
        Thread.ofVirtual().start(service::refrescar);
    }
}
