package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public final class OutfitsDtos {

    private OutfitsDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Outfit {
        private String genero;
        private boolean partial;
        private double totalEstimado;
        private boolean presupuestoExcedido;
        private List<SlotPick> slots;
        private double totalSuplementos;
        private List<SuplementoPick> suplementos;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SlotPick {
        private String slot;
        private String sitio;
        private String nombre;
        private double precio;
        private String url;
        private String img;
        private String categoria;
        private String marca;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SuplementoPick {
        private String tipo;
        private String sitio;
        private String nombre;
        private double precio;
        private String url;
        private String img;
        private String marca;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Builder {
        private String status;
        private List<SlotPick> slots;
        private String genero;
        private double presupuesto;
        private double totalEstimado;
        private boolean noCumplePresupuesto;
        private List<String> categoriasVacias;
        private List<String> categoriasSinPresupuesto;
        private String reason;
        private Double minimoBudgetNecesario;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SuplementoTipos {
        private List<Tipo> tipos;
    }

    /**
     * {@code grupo} is an explicit null for "Otros" so the client need not tell "no group" from
     * "field missing".
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Tipo {
        private String tipo;
        private String grupo;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SuplementosBuilder {
        private List<SuplementoPick> picks;
        private List<String> sinStock;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Guardado {
        private boolean ok;
        private int id;
        private String nombre;
        private double totalEstimado;
    }
}
