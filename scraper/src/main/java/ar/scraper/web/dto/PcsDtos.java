package ar.scraper.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public final class PcsDtos {

    private PcsDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Preferencia {
        private String gama;
        private Double presupuesto;
        private boolean conGpu;
        private String ddr;
        private String marcaCpu;
        private String marcaGpu;
        private String tipoAlmacenamiento;
        private boolean ramDual;
        private boolean wifi;
        private Integer capacidadMinimaGb;
        private String tamanioGabinete;
        private String tipoCooler;
        private Integer wattsMinimos;
        private String uso;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Guardada {
        private boolean ok;
        private int id;
        private String nombre;
        private double totalEstimado;
    }
}
