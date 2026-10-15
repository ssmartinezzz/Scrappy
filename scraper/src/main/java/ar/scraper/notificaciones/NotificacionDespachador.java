package ar.scraper.notificaciones;

import java.util.List;
import java.util.Map;

/**
 * Where claimed outbox rows go next. Today the adapter renders and mails them; a Kafka adapter
 * would publish them instead (a batch send, one record per recipient), and nothing upstream of
 * this port would change.
 */
public interface NotificacionDespachador {

    /**
     * Delivers each recipient's drops together and reports one outcome per recipient. A recipient
     * missing from the result is treated as failed.
     */
    Map<String, Entrega> despachar(Map<String, List<BajaReclamada>> bajasPorDestinatario);
}
