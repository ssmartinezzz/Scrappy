package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public final class ComparadorDtos {

    private ComparadorDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Grupo {
        private String nombre;
        private String categoria;
        private String img;
        private int sitios;
        private double precioMin;
        private double precioMax;
        private double ahorroPct;
        private List<Precio> precios;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Precio {
        private String sitio;
        private double precio;
        private String url;
        private String img;
        private Double precioOrig;
        private String badge;
        /** {@code en_stock} | {@code sin_stock} | {@code desconocido}, derived at read time. */
        private String stock;
        private String vistoEnStockAt;
        private String stockChequeadoAt;
    }

    /** The same article in another store, with that store's price series ({@code puntos}, {@code min}...). */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Hermano {
        private String sitio;
        private String url;
        private String key;
        private double precio;
        private String img;
        /** {@code en_stock} | {@code sin_stock} | {@code desconocido}, derived at read time. */
        private String stock;
        private JsonNode historial;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class BusquedaExterna {
        private String searchUrl;
        private String queryUsada;
        private List<ResultadoExterno> resultados;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ResultadoExterno {
        private String titulo;
        private double precio;
        private String url;
        private String thumbnail;
        private String condicion;
        private String sitio;
        private String fecha;
    }
}
