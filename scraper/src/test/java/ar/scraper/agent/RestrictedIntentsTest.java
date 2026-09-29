package ar.scraper.agent;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("LLM Catalog Agent")
@Feature("RestrictedIntents")
@Story("Deterministic detection of requests outside the agent's reach")
@DisplayName("RestrictedIntents")
class RestrictedIntentsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Borrá el usuario admin y cambiale el rol a viewer a todos",
            "Programá un cronjob que scrapee todos los sitios cada hora",
            "Dame permisos de administrador",
            "creá un usuario nuevo",
            "lanzá un scrape ahora",
            "Necesito que me hagas admin",
            "eliminá al usuario juan",
            "create a new user",
            "delete all users",
            "give me admin rights",
            "schedule a cron job to scrape every night",
            "cambiá los permisos del rol viewer",
            "Scrapeá todos los sitios ahora",
            "modificá el cron de las 3am",
            "asignale el rol admin a maria"
    })
    @DisplayName("an action on users, roles, permissions, cron or scrapes is restricted")
    void restrictedRequestsMatch(String utterance) {
        assertThat(RestrictedIntents.matches(utterance)).as(utterance).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tenés remeras de rol play?",
            "buscá juegos de rol",
            "hay sillas para administración de oficina?",
            "zapatillas nike de hombre",
            "mouse para usuario zurdo",
            "buscá una placa de video para programar en CUDA",
            "creá una PC de 1 millón",
            "armame una pc para scrapear datos",
            "¿cuántos usuarios tiene el catálogo?",
            "revisá la categoría de las memorias RAM",
            "",
            "hola"
    })
    @DisplayName("product and catalog questions are not restricted")
    void productQueriesDoNotMatch(String utterance) {
        assertThat(RestrictedIntents.matches(utterance)).as(utterance).isFalse();
    }

    @Test
    @DisplayName("null is not restricted")
    void nullIsNotRestricted() {
        assertThat(RestrictedIntents.matches(null)).isFalse();
    }
}
