package ar.scraper.classification;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * The rubro if-chain exactly as it was before the taxonomy moved into tables, kept as the oracle of
 * {@code RubroResolverEquivalenceTest}. Do not edit it: its whole value is that it is the old behaviour. The one line that
 * changed is {@code catEsHogar}: the appliances left {@code CATEGORIAS_HOGAR} (V139) and the old chain still counts them as home.
 */
final class RubroResolverLegacy {

    private static final Map<String, String> RUBRO_POR_SUBCATEGORIA = Map.of(
            "Silla|comedor", "hogar", "Silla|exterior", "hogar", "Silla|infantil", "hogar");

    private final SiteRegistry siteRegistry;

    RubroResolverLegacy(SiteRegistry siteRegistry) {
        this.siteRegistry = siteRegistry;
    }

    private static String rubroDeSubcategoria(String cat, String sub) {
        if (cat == null || sub == null) return "";
        return RUBRO_POR_SUBCATEGORIA.getOrDefault(cat + "|" + sub, "");
    }

    public String resolver(String sitioKey, String cat, String rubroExistente, String sub) {
        boolean catEsTextil = CategoryGroups.esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = CategoryGroups.esCategoriaSuplemento(cat);
        boolean catEsConstr = CategoryGroups.esCategoriaConstruccion(cat);
        boolean catEsHogar  = CategoryGroups.esCategoriaHogar(cat) || CategoryGroups.esCategoriaElectrodomesticos(cat);
        SiteRegistry.Sitio sitio = siteRegistry.porKey(sitioKey);
        String rubroForzado = sitio.rubroForzado();
        String rubroPorDefecto = sitio.rubroPorDefecto();
        String rubroDeSub = rubroDeSubcategoria(cat, sub);

        if ("tecnologia".equals(rubroForzado) && !catEsTextil) {
            return "tecnologia";
        } else if ("oficina".equals(rubroForzado) && !catEsTextil && !catEsSuppl) {
            return "oficina";
        } else if (CategoryGroups.esCategoriaBelleza(cat)) {
            return "belleza";
        } else if (catEsConstr) {
            return "construccion";
        } else if (catEsHogar) {
            return "hogar";
        } else if (catEsSuppl) {
            return "suplementos";
        } else if ("suplementos".equals(rubroForzado) && !catEsTextil) {
            return "suplementos";
        } else if ("construccion".equals(rubroForzado) && !catEsTextil) {
            return "construccion";
        } else if ("hogar".equals(rubroForzado) && !catEsTextil) {
            return "hogar";
        } else if (catEsTextil) {
            return "indumentaria";
        } else if (StringUtils.isBlank(rubroForzado) && !rubroDeSub.isEmpty()) {
            return rubroDeSub;
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.categoriasOficina().contains(cat)) {
            return "oficina";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaDeporte(cat)) {
            return "deporte";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaLibros(cat)) {
            return "libros";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaJuguetes(cat)) {
            return "juguetes";
        } else if (StringUtils.isBlank(rubroForzado) && (CategoryGroups.esCategoriaTech(cat) || CategoryGroups.esCategoriaCelulares(cat))) {
            return "tecnologia";
        } else if (StringUtils.isNotBlank(rubroPorDefecto)) {
            return rubroPorDefecto;
        } else if (StringUtils.isNotBlank(rubroExistente)) {
            return rubroExistente;
        } else {
            return "indumentaria";
        }
    }
}
