package ar.scraper.classification;


import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/** Curated-brand extraction with site fallback. */
public class BrandExtractor {

    public static final List<String> MARCAS = List.of(
        "Nike","Adidas","Puma","Reebok","New Balance","Asics","Saucony","Brooks",
        "Hoka","On Running","Salomon","Mizuno","Under Armour","Fila","Umbro",
        "Vans","Converse","DC","Etnies","Volcom","Quiksilver","Billabong",
        "The North Face","Columbia","Patagonia","Timberland","Merrell",
        "Topper","Flecha","Jaguar","Gola","Penalty","Olympikus",
        "Lacoste","Tommy","Calvin Klein","Levi's","Levis","Wrangler",
        "Champion","Kappa","Ellesse","Le Coq Sportif","Fred Perry",
        "Caterpillar","Keen","Palladium","Crocs","Birkenstock",
        "Bulks","Fuark","Harvey Willys","Harvey",
        // Sin estas entradas la lista era 100% indumentaria y calzado, así que TODO suplemento caía
        // al fallback por sitio: una whey de ENA vendida por Entreno quedaba con marca "Entreno".
        // Sólo formas que se sostienen solas:
        "Gold Nutrition","Star Nutrition","Xtrenght","ENA","BSA",
        "Labs Nutrition","Body Advance","Grosz Nutrition","Optimum Nutrition",
        "Universal Nutrition","ETH Nutrition","BSN","Nutrex","Leguilab",
        "Innovanaturals","Mervick","Granger","Crudda","Gentech","PGN","Orihens",
        "Natulabs","AMPK","Pont","Natuliv","Entrenuts","Cellucor","Diabla","Muecas"
    );

    private static final List<Pattern> MARCA_PATTERNS = MARCAS.stream()
            .map(m -> Pattern.compile("\\b" + Pattern.quote(m.toLowerCase()) + "\\b"))
            .collect(Collectors.toList());

    public String extraer(String nombre, String sitio) {
        if (StringUtils.isBlank(nombre)) return "";
        String lower = nombre.toLowerCase();

        for (int i = 0; i < MARCAS.size(); i++) {
            if (MARCA_PATTERNS.get(i).matcher(lower).find()) return MARCAS.get(i);
        }

        return "";
    }
}
