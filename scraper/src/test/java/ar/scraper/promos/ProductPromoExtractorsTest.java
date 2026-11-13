package ar.scraper.promos;

import ar.scraper.model.promo.PromoFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Product promo extractors — abstain unless the payload proves the promo")
class ProductPromoExtractorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate HOY = LocalDate.of(2026, 11, 13);

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> resumen(List<PromoFinanciacion> promos) {
        return promos.stream().map(p -> p.medioPago() + "|" + p.tipo() + "|" + p.cuotas() + "|" + p.pct()).toList();
    }

    @Test
    @DisplayName("VTEX: an installment with interest never counts, even when it is the largest")
    void vtexIgnoresInterest() {
        JsonNode oferta = json("""
                {"Installments":[
                  {"PaymentSystemName":"Visa","NumberOfInstallments":6,"InterestRate":0.0},
                  {"PaymentSystemName":"Visa","NumberOfInstallments":12,"InterestRate":7.5},
                  {"PaymentSystemName":"Mastercard","NumberOfInstallments":1,"InterestRate":0.0}]}""");

        assertThat(resumen(new VtexPromoExtractor().extraer(oferta, HOY)))
                .containsExactly("Visa|CUOTAS_SIN_INTERES|6|null");
    }

    @Test
    @DisplayName("VTEX: a teaser or highlight naming a promo goes through the text parser, either field spelling")
    void vtexTeasersGoThroughTheParser() {
        JsonNode oferta = json("""
                {"Installments":[],
                 "PromotionTeasers":[{"Name":"15% de descuento con transferencia"}],
                 "DiscountHighLight":[{"<Name>k__BackingField":"3 cuotas sin interes con Visa"}]}""");

        assertThat(resumen(new VtexPromoExtractor().extraer(oferta, HOY)))
                .containsExactlyInAnyOrder("Transferencia|DESCUENTO|null|15.00", "Visa|CUOTAS_SIN_INTERES|3|null");
    }

    @Test
    @DisplayName("VTEX: no Installments field at all is no promo")
    void vtexMissingInstallments() {
        assertThat(new VtexPromoExtractor().extraer(json("{\"Price\":10}"), HOY)).isEmpty();
    }

    @Test
    @DisplayName("Vaypol: a value that does not reproduce the price (interest) abstains")
    void vaypolWithInterestAbstains() {
        JsonNode item = json("""
                {"all_prices":{"original":100000,"sale_price":null,"installments":{"quantity":6,"value":19000}}}""");

        assertThat(new VaypolPromoExtractor().extraer(item, HOY)).isEmpty();
    }

    @Test
    @DisplayName("Vaypol: the sale price, not the original, is what the installments must add up to")
    void vaypolUsesSalePrice() {
        JsonNode item = json("""
                {"all_prices":{"original":179999,"sale_price":116999,"installments":{"quantity":6,"value":30000}}}""");

        assertThat(new VaypolPromoExtractor().extraer(item, HOY)).isEmpty();
    }

    @Test
    @DisplayName("Vaypol: one installment, or none, is not a promo")
    void vaypolSingleOrMissing() {
        assertThat(new VaypolPromoExtractor().extraer(json(
                "{\"all_prices\":{\"original\":1000,\"installments\":{\"quantity\":1,\"value\":1000}}}"), HOY)).isEmpty();
        assertThat(new VaypolPromoExtractor().extraer(json("{\"all_prices\":{\"original\":1000}}"), HOY)).isEmpty();
    }

    @Test
    @DisplayName("Kevingston: more than one peso off the price abstains; exactly one peso still counts")
    void kevingstonBoundary() {
        assertThat(new KevingstonPromoExtractor().extraer(json(
                "{\"precio\":\"1000.00\",\"cuotas\":\"2 cuotas de $499,50*\"}"), HOY)).hasSize(1);
        assertThat(new KevingstonPromoExtractor().extraer(json(
                "{\"precio\":\"1000.00\",\"cuotas\":\"2 cuotas de $520*\"}"), HOY)).isEmpty();
    }

    @Test
    @DisplayName("Kevingston: empty or unreadable cuotas text is no promo")
    void kevingstonNoText() {
        assertThat(new KevingstonPromoExtractor().extraer(json("{\"precio\":\"1000.00\",\"cuotas\":\"\"}"), HOY)).isEmpty();
        assertThat(new KevingstonPromoExtractor().extraer(json("{\"precio\":\"1000.00\"}"), HOY)).isEmpty();
    }

    @Test
    @DisplayName("Gamer Factory: special equal to list, or a missing list, is no discount")
    void gamerFactoryNoDiscount() {
        assertThat(new GamerFactoryPromoExtractor().extraer(json(
                "{\"price\":{\"special\":{\"ARS\":100},\"list\":{\"ARS\":100}}}"), HOY)).isEmpty();
        assertThat(new GamerFactoryPromoExtractor().extraer(json(
                "{\"price\":{\"special\":{\"ARS\":100}}}"), HOY)).isEmpty();
        assertThat(new GamerFactoryPromoExtractor().extraer(json(
                "{\"price\":{\"special\":{\"ARS\":120},\"list\":{\"ARS\":100}}}"), HOY)).isEmpty();
    }

    @Test
    @DisplayName("Gamer Factory: the percentage is of list, rounded to two decimals")
    void gamerFactoryPercentage() {
        assertThat(resumen(new GamerFactoryPromoExtractor().extraer(json(
                "{\"price\":{\"special\":{\"ARS\":246000},\"list\":{\"ARS\":281000}}}"), HOY)))
                .containsExactly("Transferencia|DESCUENTO|null|12.46");
    }

    @Test
    @DisplayName("Inpro: the value spelling of the localized description is read when 'es' is absent")
    void inproValueFallback() {
        JsonNode p = json("{\"seo_description\":{\"value\":\"10% de descuento con transferencia.\"}}");

        assertThat(resumen(new InproPromoExtractor().extraer(p, HOY))).containsExactly("Transferencia|DESCUENTO|null|10.00");
        assertThat(new InproPromoExtractor().extraer(json("{}"), HOY)).isEmpty();
    }
}
