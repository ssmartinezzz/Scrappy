package ar.scraper.indices;

public enum Indice {
    IPC(Frecuencia.MENSUAL, "inflacion"),
    USD_OFICIAL(Frecuencia.DIARIO, "dolar oficial");

    private final Frecuencia frecuencia;
    private final String etiqueta;

    Indice(Frecuencia frecuencia, String etiqueta) {
        this.frecuencia = frecuencia;
        this.etiqueta = etiqueta;
    }

    public Frecuencia frecuencia() {
        return frecuencia;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public enum Frecuencia { MENSUAL, DIARIO }
}
