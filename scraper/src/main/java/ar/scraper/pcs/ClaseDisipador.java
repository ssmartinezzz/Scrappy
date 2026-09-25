package ar.scraper.pcs;

/**
 * Air-cooler build class read off a Cooler part's name — {@code DOBLE_TORRE}
 * (two towers/two heatsink stacks) beats {@code TORRE} (a single tower).
 * {@code DESCONOCIDA} is abstention ("the name didn't say") and, like {@link
 * TipoCooler#DESCONOCIDO}, is NOT a rung of the DOBLE_TORRE &gt; TORRE
 * scale: {@link #esConocida()} models that explicitly instead of leaning on
 * the enum's declaration order, same reasoning as {@link Gama}'s javadoc.
 *
 * <p>Pedido del usuario (2026-09-25, pc-builder-homelab T16): "no importa
 * qué gama, en los cooler siempre estaba ganando uno medio pedorro" — hasta
 * T16 los 85 coolers AIRE del catálogo tenían {@code radiadorMm=0} (ese eje
 * es sólo de líquidos) y empataban en TODO, así que el más barato ganaba
 * siempre ({@code CPU Cooler Raptor Cryo RGB - 3P}, $18.400). Esta clase le
 * da al eje AIRE una magnitud de potencia real, igual que {@link
 * TamanioGabinete} se la dio al gabinete.</p>
 *
 * <p>NO se persiste en {@code producto_tech_specs} en esta fase — mismo
 * precedente que {@code nivel} (D7, pc-builder-top-tier fase 8): el armador
 * lo calcula al armar, desde el snapshot en memoria.</p>
 */
public enum ClaseDisipador {
    DOBLE_TORRE, TORRE, DESCONOCIDA;

    public boolean esConocida() {
        return this != DESCONOCIDA;
    }
}
