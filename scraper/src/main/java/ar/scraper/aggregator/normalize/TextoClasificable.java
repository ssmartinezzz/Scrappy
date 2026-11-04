package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

record TextoClasificable(String crudo, String normalizado, String sinPrefijoDeVenta) {

    private static final Pattern PESO_VOLUMEN =
        Pattern.compile("\\d+\\s*(g|ml|kg|mg|oz|l)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PREFIJO_DE_VENTA = Pattern.compile(
        "^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|x?\\d+(?:[.,]\\d+)?)\\s+)+");

    private static final String LIDER_OUTLET = " outlet ";

    static TextoClasificable de(String crudo) {
        String normalizado = " " + crudo.toLowerCase()
                .replaceAll("[áàä]", "a").replaceAll("[éèë]", "e")
                .replaceAll("[íìï]", "i").replaceAll("[óòö]", "o")
                .replaceAll("[úùü]", "u").replaceAll("[ñ]", "n") + " ";
        return new TextoClasificable(crudo, normalizado, PREFIJO_DE_VENTA.matcher(normalizado).replaceFirst(" "));
    }

    static boolean tieneIndicadorPeso(String texto) {
        if (StringUtils.isBlank(texto)) return false;
        return PESO_VOLUMEN.matcher(texto.trim()).find();
    }

    boolean tieneIndicadorPeso() {
        return tieneIndicadorPeso(crudo);
    }

    boolean contiene(String[] keywords) {
        return GarmentTaxonomy.anyMatch(normalizado, keywords);
    }

    boolean contiene(String fragmento) {
        return normalizado.contains(fragmento);
    }

    boolean empiezaCon(String[] keywords) {
        return empiezaConAlguna(normalizado, keywords);
    }

    boolean sinPrefijoDeVentaEmpiezaCon(String[] keywords) {
        return empiezaConAlguna(sinPrefijoDeVenta, keywords);
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
        for (String kw : keywords) {
            if (normalizado.startsWith(kw)) return true;
        }
        return false;
    }

    private static boolean empiezaConAlguna(String texto, String[] keywords) {
        for (String kw : keywords) {
            if (texto.startsWith(kw)) return true;
        }
        if (!texto.startsWith(LIDER_OUTLET)) return false;
        String sinOutlet = " " + texto.substring(LIDER_OUTLET.length()).replaceFirst("^[-\\s]+", "");
        for (String kw : keywords) {
            if (sinOutlet.startsWith(kw)) return true;
        }
        return false;
    }
}
