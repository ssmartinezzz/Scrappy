package ar.scraper.indices;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IndiceRefreshJob {

    private final IndiceService service;

    public void alArrancar() {
        service.cargarDesdeDB();
        Thread.ofVirtual().start(service::refrescar);
    }

    public void diario() {
        Thread.ofVirtual().start(service::refrescar);
    }
}
