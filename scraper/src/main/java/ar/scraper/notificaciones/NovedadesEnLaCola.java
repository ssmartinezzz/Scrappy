package ar.scraper.notificaciones;

/**
 * "There is something new in the outbox, look now." Fired from the database push channel;
 * the poll interval stays as the fallback when a signal is lost.
 */
public interface NovedadesEnLaCola {

    void hayNovedades();
}
