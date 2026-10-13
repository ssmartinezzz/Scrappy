package ar.scraper.pcs.specs;

/** Benchmark score by canonical model key ({@link ClaveDeModelo}); 0 is abstention, never a score. */
public interface TablaDeRendimiento {

    int puntaje(String clave);
}
