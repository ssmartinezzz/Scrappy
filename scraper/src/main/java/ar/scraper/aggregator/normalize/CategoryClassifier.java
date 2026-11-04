package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

import org.apache.commons.lang3.StringUtils;

@Component
public class CategoryClassifier {

    private static final NutricionClassifier NUTRICION = new NutricionClassifier();

    private static final FamiliaClassifier[] FAMILIAS_EN_ORDEN = {
        new HogarClassifier(),
        new ConstruccionClassifier(),
        new InstalacionesClassifier(),
        new HerramientasClassifier(),
        new FerreteriaClassifier(),
        new BanoCocinaClassifier(),
        new MueblesClassifier(),
        new OrganizacionHogarClassifier(),
        new TextilDecoClassifier(),
        new NutricionTempranaClassifier(NUTRICION),
        new OficinaClassifier(),
        new TechClassifier(),
        new ConjuntoClassifier(),
        new EquipamientoDeportivoClassifier(),
        new CalzadoClassifier(),
        new IndumentariaClassifier(),
        NUTRICION,
        new AccesoriosClassifier(),
        new CalzadoPorModeloClassifier()
    };

    public String normalizarCategoria(String raw, String nombre) {
        String fromName = clasificar(nombre);
        if (!fromName.isEmpty()) return fromName;

        if (StringUtils.isNotBlank(raw)) {
            String fromRaw = clasificar(raw);
            if (!fromRaw.isEmpty()) return fromRaw;
            String cleaned = raw.replaceAll("(?i)\\b(vcp|sporting|vaypol|freres|batuk|city|bulks|"
                           + "midway|tussy|bullbenny|dcshoes|eldon|entreno|fuark)\\b", "")
                           .replaceAll("[>|/\\\\]+", " ")
                           .replaceAll("\\s{2,}", " ")
                           .trim();
            String alias = CategoryAliases.canonical(cleaned.split("\\s+")[0]);
            if (alias != null) return alias;
            String aliasCompleto = CategoryAliases.canonical(cleaned);
            if (aliasCompleto != null) return aliasCompleto;
        }
        if (TextoClasificable.tieneIndicadorPeso(nombre)) return "Alimentos";
        return "Otros";
    }

    private String clasificar(String texto) {
        if (StringUtils.isBlank(texto)) return "";
        if (NonTextileGuard.esClaramenteNoTextil(texto)) return "";
        TextoClasificable clasificable = TextoClasificable.de(texto);
        for (FamiliaClassifier familia : FAMILIAS_EN_ORDEN) {
            String categoria = familia.clasificar(clasificable);
            if (!categoria.isEmpty()) return categoria;
        }
        return "";
    }
}
