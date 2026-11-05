package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

record TextoClasificable(String crudo, String normalizado, String sinPrefijoDeVenta, ArraysEncontrados arrays) {

    private static final Pattern PESO_VOLUMEN =
        Pattern.compile("\\d+\\s*(g|ml|kg|mg|oz|l)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PREFIJO_DE_VENTA = Pattern.compile(
        "^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|x?\\d+(?:[.,]\\d+)?)\\s+)+");

    private static final String LIDER_OUTLET = " outlet ";

    static TextoClasificable de(String crudo) {
        String normalizado = " " + AccentStripper.strip(crudo.toLowerCase()) + " ";
        String sinPrefijoDeVenta = PREFIJO_DE_VENTA.matcher(normalizado).replaceFirst(" ");
        return new TextoClasificable(crudo, normalizado, sinPrefijoDeVenta,
            ArraysEncontrados.de(normalizado, sinPrefijoDeVenta));
    }

    static boolean tieneIndicadorPeso(String texto) {
        if (StringUtils.isBlank(texto)) return false;
        return PESO_VOLUMEN.matcher(texto.trim()).find();
    }

    boolean tieneIndicadorPeso() {
        return tieneIndicadorPeso(crudo);
    }

    boolean contiene(String[] keywords) {
        int id = IndiceDePalabrasClave.idDe(keywords);
        if (id < 0) return GarmentTaxonomy.anyMatch(normalizado, keywords);
        return IndiceDePalabrasClave.marcado(arrays.contenidosEnNormalizado(), id);
    }

    boolean contiene(String fragmento) {
        return normalizado.contains(fragmento);
    }

    boolean empiezaCon(String[] keywords) {
        return empiezaConAlguna(normalizado, arrays.prefijosDeNormalizado(), keywords);
    }

    boolean sinPrefijoDeVentaEmpiezaCon(String[] keywords) {
        return empiezaConAlguna(sinPrefijoDeVenta, arrays.prefijosDeSinPrefijoDeVenta(), keywords);
    }

    boolean lideraSinVeto(String[] lideres, String[] vetos) {
        return sinPrefijoDeVentaEmpiezaCon(lideres) && !contiene(vetos);
    }

    boolean lideraUnNombreCompuestoSinVeto(String[] lideres, String[] vetos) {
        for (String lider : lideres) {
            if (sinPrefijoDeVenta.equals(lider)) return false;
        }
        return lideraSinVeto(lideres, vetos);
    }

    boolean lideraConCalificador(String[] lideres, String[] calificadores) {
        return sinPrefijoDeVentaEmpiezaCon(lideres) && contiene(calificadores);
    }

    boolean empiezaLiteralmenteCon(String[] keywords) {
        return empiezaConAlgunaSinReintento(normalizado, arrays.prefijosDeNormalizado(), keywords);
    }

    private static boolean empiezaConAlgunaSinReintento(String texto, long[] prefijos, String[] keywords) {
        int id = IndiceDePalabrasClave.idDe(keywords);
        if (id >= 0) return IndiceDePalabrasClave.marcado(prefijos, id);
        for (String kw : keywords) {
            if (texto.startsWith(kw)) return true;
        }
        return false;
    }

    private static boolean empiezaConAlguna(String texto, long[] prefijos, String[] keywords) {
        if (empiezaConAlgunaSinReintento(texto, prefijos, keywords)) return true;
        if (!texto.startsWith(LIDER_OUTLET)) return false;
        String sinOutlet = " " + texto.substring(LIDER_OUTLET.length()).replaceFirst("^[-\\s]+", "");
        for (String kw : keywords) {
            if (sinOutlet.startsWith(kw)) return true;
        }
        return false;
    }
}
