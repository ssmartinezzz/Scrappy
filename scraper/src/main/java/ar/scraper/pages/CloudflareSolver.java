package ar.scraper.pages;

import com.microsoft.playwright.BrowserContext;

/**
 * Seam para pedir un clearance fresco cuando el challenge de Cloudflare reaparece a mitad de un
 * crawl largo. Devuelve {@code false} si no se pudo refrescar: ahí el page corta con
 * {@link CloudflareBlockedException} en vez de degradar a un catálogo vacío.
 */
@FunctionalInterface
public interface CloudflareSolver {
    boolean refrescar(BrowserContext ctx);
}
