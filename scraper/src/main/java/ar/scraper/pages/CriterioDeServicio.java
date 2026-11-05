package ar.scraper.pages;

@FunctionalInterface
public interface CriterioDeServicio {
    boolean esServicio(String nombreCategoria);
}
