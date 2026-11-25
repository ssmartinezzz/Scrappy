package ar.scraper.classification;

public interface RubroSource {

    /** The whole rubro taxonomy; throws a runtime exception when the read fails or the taxonomy is empty. */
    RubroCatalogo cargar();
}
