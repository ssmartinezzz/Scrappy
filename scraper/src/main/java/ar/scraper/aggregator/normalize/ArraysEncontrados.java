package ar.scraper.aggregator.normalize;

record ArraysEncontrados(long[] contenidosEnNormalizado, long[] prefijosDeNormalizado, long[] prefijosDeSinPrefijoDeVenta) {

    static ArraysEncontrados de(String normalizado, String sinPrefijoDeVenta) {
        return new ArraysEncontrados(
            IndiceDePalabrasClave.arraysContenidosEn(normalizado),
            IndiceDePalabrasClave.arraysQueEmpiezanEl(normalizado),
            IndiceDePalabrasClave.arraysQueEmpiezanEl(sinPrefijoDeVenta));
    }
}
