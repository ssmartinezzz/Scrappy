package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import ar.scraper.aggregator.text.AccentStripper;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single owner of Spanish promo text -> {@link PromoFinanciacion}. Deterministic and regex-based.
 * It abstains rather than guess: a percentage with no payment medium, a coupon, shipping, or
 * "6 cuotas de $X" (installments of an amount, not stated interest-free) produce nothing.
 *
 * <p>Rules worth knowing: installments count only in 2..36; each event takes the media named after
 * it up to the next event (else the ones before it); installments without a named medium are
 * "Tarjeta de crédito"; vigencia is read from the whole text and its year defaults to the reference
 * year (a date already past is never rolled forward; only an end date earlier than its start is).
 */
public final class PromoTextParser {

    private static final int CUOTAS_MIN = 2;
    private static final int CUOTAS_MAX = 36;
    private static final int TEXTO_FUENTE_MAX = 300;

    private static final MedioPago TARJETA_CREDITO = new MedioPago("Tarjeta de crédito", TipoMedio.MEDIO);
    private static final Set<String> GENERICOS_DE_TARJETA = Set.of("Tarjeta de crédito", "Tarjeta de débito");
    private static final Set<String> NO_FINANCIABLES = Set.of("Transferencia", "Efectivo");

    private static final Pattern SEGMENTOS = Pattern.compile(
            "\\s*(?:[;|\\n\\r!]+|\\.(?=\\s|$)|\"[^\"]*\"|\u201C[^\u201D]*\u201D)\\s*");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private static final Pattern CUOTAS = Pattern.compile(
            "(?<![\\d/.,$%])(\\d{1,2}(?:\\s*(?:,|y(?:\\s+hasta)?|e)\\s*\\d{1,2})*)\\s*(?:cuotas?|ctas?)\\s+(?:fijas\\s+)?"
                    + "(?:sin\\s+inter(?:es)?\\b|s/\\s*inter(?:es)?\\b|sin\\s+int\\b)");

    private static final String NUM = "(\\d{1,3}(?:[.,]\\d{1,2})?)";
    private static final Pattern PCT_ANTES = Pattern.compile(
            "(?<![\\d.,])" + NUM + "\\s*%\\s*(?:de\\s+)?"
                    + "(off|descuento|dto|desc|reintegro|ahorro|bonificacion|reembolso|cashback|recargo)\\b");
    private static final Pattern PCT_DESPUES = Pattern.compile(
            "\\b(descuento|dto|recargo|reintegro|bonificacion|ahorro)\\s+(?:del?|de un)?\\s*" + NUM + "\\s*%");

    private static final Pattern NO_ES_PAGO = Pattern.compile(
            "primera compra|cupon|codigo|newsletter|suscrib|2x1|envio");

    private static final String FECHA = "(\\d{1,2})(?:/(\\d{1,2})|\\s+de\\s+([a-z]+))(?:(?:/|\\s+de\\s+)(\\d{4}|\\d{2}))?";
    private static final Pattern DEL_AL = Pattern.compile(
            "\\b(?:del?|desde)(?:\\s+el)?(?:\\s+dia)?\\s+" + FECHA + "\\s+(?:al|a|hasta)(?:\\s+el)?\\s+" + FECHA);
    private static final Pattern HASTA = Pattern.compile("\\bhasta(?:\\s+el)?(?:\\s+dia)?\\s+" + FECHA);

    private static final Map<String, Integer> MESES = Map.ofEntries(
            Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abril", 4),
            Map.entry("mayo", 5), Map.entry("junio", 6), Map.entry("julio", 7), Map.entry("agosto", 8),
            Map.entry("septiembre", 9), Map.entry("setiembre", 9), Map.entry("octubre", 10),
            Map.entry("noviembre", 11), Map.entry("diciembre", 12));

    private record Evento(int ini, int fin, TipoPromo tipo, List<Integer> cuotas, BigDecimal pct) {}

    private PromoTextParser() {}

    public static List<PromoFinanciacion> parsear(String texto, Alcance alcance, LocalDate referencia) {
        if (StringUtils.isBlank(texto)) return List.of();
        LocalDate[] vigencia = vigencia(normalizar(texto), referencia);
        Set<PromoFinanciacion> out = new LinkedHashSet<>();
        for (String crudo : SEGMENTOS.split(texto)) {
            if (crudo.isBlank()) continue;
            String segmento = normalizar(crudo);
            String fuente = StringUtils.abbreviate(crudo.strip(), TEXTO_FUENTE_MAX);
            List<Evento> eventos = eventos(segmento);
            for (int i = 0; i < eventos.size(); i++) {
                Evento ev = eventos.get(i);
                for (MedioPago medio : medios(segmento, eventos, i)) {
                    if (ev.tipo() == TipoPromo.CUOTAS_SIN_INTERES) {
                        for (int c : ev.cuotas()) {
                            out.add(new PromoFinanciacion(medio.nombre(), medio.tipo(), ev.tipo(), c, null,
                                    alcance, vigencia[0], vigencia[1], fuente));
                        }
                    } else {
                        out.add(new PromoFinanciacion(medio.nombre(), medio.tipo(), ev.tipo(), null, ev.pct(),
                                alcance, vigencia[0], vigencia[1], fuente));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static String normalizar(String s) {
        return ESPACIOS.matcher(AccentStripper.strip(s.toLowerCase(Locale.ROOT))).replaceAll(" ").trim();
    }

    private static List<Evento> eventos(String seg) {
        List<Evento> out = new ArrayList<>();
        Matcher m = CUOTAS.matcher(seg);
        while (m.find()) {
            List<Integer> cuotas = new ArrayList<>();
            for (String n : m.group(1).split("\\D+")) {
                int c = Integer.parseInt(n);
                if (c >= CUOTAS_MIN && c <= CUOTAS_MAX) cuotas.add(c);
            }
            if (!cuotas.isEmpty()) out.add(new Evento(m.start(), m.end(), TipoPromo.CUOTAS_SIN_INTERES, cuotas, null));
        }
        if (!NO_ES_PAGO.matcher(seg).find()) {
            descuentos(PCT_ANTES.matcher(seg), 1, 2, out);
            descuentos(PCT_DESPUES.matcher(seg), 2, 1, out);
        }
        out.sort(Comparator.comparingInt(Evento::ini));
        return out;
    }

    private static void descuentos(Matcher m, int grupoPct, int grupoPalabra, List<Evento> out) {
        while (m.find()) {
            BigDecimal pct = new BigDecimal(m.group(grupoPct).replace(',', '.'));
            if (pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) continue;
            TipoPromo tipo = "recargo".equals(m.group(grupoPalabra)) ? TipoPromo.RECARGO : TipoPromo.DESCUENTO;
            out.add(new Evento(m.start(), m.end(), tipo, List.of(), pct));
        }
    }

    private static List<MedioPago> medios(String seg, List<Evento> eventos, int i) {
        Evento ev = eventos.get(i);
        int finVentana = i + 1 < eventos.size() ? eventos.get(i + 1).ini() : seg.length();
        List<MedioPago> medios = MediosDePago.encontrarEn(seg.substring(ev.fin(), Math.max(ev.fin(), finVentana)));
        if (medios.isEmpty()) {
            int iniVentana = i > 0 ? eventos.get(i - 1).fin() : 0;
            medios = MediosDePago.encontrarEn(seg.substring(Math.min(iniVentana, ev.ini()), ev.ini()));
        }
        boolean cuotas = ev.tipo() == TipoPromo.CUOTAS_SIN_INTERES;
        List<MedioPago> utiles = new ArrayList<>(medios);
        if (cuotas) utiles.removeIf(m -> NO_FINANCIABLES.contains(m.nombre()));
        if (utiles.stream().anyMatch(m -> m.tipo() != TipoMedio.MEDIO)) {
            utiles.removeIf(m -> GENERICOS_DE_TARJETA.contains(m.nombre()));
        }
        if (utiles.isEmpty() && cuotas) utiles.add(TARJETA_CREDITO);
        return utiles;
    }

    /** {desde, hasta}, either possibly null. */
    private static LocalDate[] vigencia(String texto, LocalDate ref) {
        Matcher m = DEL_AL.matcher(texto);
        if (m.find()) {
            LocalDate desde = fecha(m.group(1), m.group(2), m.group(3), m.group(4), ref.getYear());
            LocalDate hasta = fecha(m.group(5), m.group(6), m.group(7), m.group(8), ref.getYear());
            if (desde != null && hasta != null && m.group(8) == null && hasta.isBefore(desde)) {
                hasta = hasta.plusYears(1);
            }
            return new LocalDate[] {desde, hasta};
        }
        m = HASTA.matcher(texto);
        if (m.find()) return new LocalDate[] {null, fecha(m.group(1), m.group(2), m.group(3), m.group(4), ref.getYear())};
        return new LocalDate[] {null, null};
    }

    private static LocalDate fecha(String dia, String mesNum, String mesNombre, String anio, int anioRef) {
        Integer mes = mesNum != null ? Integer.valueOf(mesNum) : MESES.get(mesNombre);
        if (mes == null) return null;
        int y = anio == null ? anioRef : anio.length() == 2 ? 2000 + Integer.parseInt(anio) : Integer.parseInt(anio);
        try {
            return LocalDate.of(y, mes, Integer.parseInt(dia));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
