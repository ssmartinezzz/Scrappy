package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Payloads of the brand browser and "mejores picks" endpoints. */
public final class MarcasPicksDtos {

    private MarcasPicksDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Marca {
        private String marca;
        private int count;
        private String rubro;
        private String img;
        private long mediana;
        private long precioMin;
        private long precioMax;
        private String topCats;
        private BestPick bestPick;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BestPick {
        private String nombre;
        private double precio;
        private String url;
        private String img;
        private String badge;
        private Integer scoreP;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class MejoresCategoria {
        private String categoria;
        private int count;
        private String rubro;
        private String imgCat;
        private long mediana;
        private List<Pick> picks;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Pick {
        private String tipo;
        private String label;
        private String nombre;
        private double precio;
        private int cantidadUnidades;
        private boolean esPack;
        private double precioUnitario;
        private String url;
        private String img;
        private String sitio;
        private String marca;
        private Integer scoreP;
        private String badge;
        private String segment;
        private Integer pctil;
        private Double precioOrig;
    }
}
