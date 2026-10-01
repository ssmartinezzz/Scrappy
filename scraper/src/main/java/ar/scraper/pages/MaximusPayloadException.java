package ar.scraper.pages;

/**
 * Thrown by {@link TechStorePage#parseMaximusPayload(String)} when the API's {@code d} field is not
 * the expected paginated-listing shape — most often the GlobalBluePoint session/module gate
 * ({@code "-2, Módulo GlobalBluePoint© GBPScripts NO ADQUIRIDO."}), returned with HTTP 200 by a
 * cookie-less call.
 */
public class MaximusPayloadException extends RuntimeException {
    public MaximusPayloadException(String dPrefix) {
        super("Maximus API devolvió un payload inesperado (posible session/module gate), primeros 120 chars: "
                + dPrefix);
    }
}
