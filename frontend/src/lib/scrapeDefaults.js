/**
 * Los defaults de la banda de precios del scrape, en un solo lugar.
 *
 * Vivían copiados como literal en `App.jsx`, `AppLayout.jsx`, `SplashPanel.jsx`
 * y `cron/CronJobCard.jsx`. Cuatro copias de un número es cómo el backend sube
 * el techo y la UI sigue lanzando scrapes con el viejo: sin error, sólo
 * productos que el scraper trae y el filtro descarta.
 *
 * Tienen que decir lo mismo que `precio.maximo` en `config.properties` y que el
 * default de `ScraperConfig.getPrecioMaximo()`. Son valores de ARRANQUE: apenas
 * `/api/status` responde, manda la config del backend.
 */

export const PRECIO_MIN_DEFAULT = 0;

/**
 * 5.000.000 desde `add-inpro-office-store`. Era 300.000, y esa banda dejaba
 * afuera el 32% del catálogo de INPRO y el 34% del de Maximus.
 */
export const PRECIO_MAX_DEFAULT = 5000000;

/** La banda por defecto, con las claves que usa el resto de la UI. */
export const CONFIG_DEFAULT = Object.freeze({
  precioMin: PRECIO_MIN_DEFAULT,
  precioMax: PRECIO_MAX_DEFAULT,
});
