package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DespachadorPorMail — renders every recipient's drops and mails the whole tick in one batch")
class DespachadorPorMailTest {

    private final PlantillaBajaPrecio plantilla = new PlantillaBajaPrecio("https://tienda.example.com");

    private static BajaReclamada baja(String nombre) {
        return new BajaReclamada(1, UUID.randomUUID(), "usuario@example.com", "https://s.test/" + nombre,
                nombre, "Sitio", 100, 80);
    }

    @Test
    @DisplayName("hands the mail port ONE batch with a message per recipient, every drop of theirs in it")
    void unLoteConUnMailPorDestinatario() {
        List<List<CorreoDirigido>> llamadas = new ArrayList<>();
        MailPort mail = correos -> {
            llamadas.add(correos);
            Map<String, Entrega> r = new LinkedHashMap<>();
            correos.forEach(c -> r.put(c.destino(), Entrega.ok()));
            return r;
        };
        Map<String, List<BajaReclamada>> envios = new LinkedHashMap<>();
        envios.put("usuario@example.com", List.of(baja("Uno"), baja("Dos")));
        envios.put("otro@example.com", List.of(baja("Tres")));

        Map<String, Entrega> resultado = new DespachadorPorMail(plantilla, mail).despachar(envios);

        assertThat(llamadas).hasSize(1);
        assertThat(llamadas.getFirst()).extracting(CorreoDirigido::destino)
                .containsExactly("usuario@example.com", "otro@example.com");
        CorreoRenderizado primero = llamadas.getFirst().getFirst().correo();
        assertThat(primero.asunto()).isEqualTo("Bajó de precio: 2 favoritos");
        assertThat(primero.html()).contains("Uno", "Dos");
        assertThat(primero.texto()).contains("Uno", "Dos");
        assertThat(resultado).containsOnlyKeys("usuario@example.com", "otro@example.com");
    }

    @Test
    @DisplayName("passes the per-recipient outcome of the mail port through untouched")
    void devuelveElResultadoPorDestinatario() {
        MailPort mail = correos -> Map.of(
                "usuario@example.com", Entrega.ok(),
                "otro@example.com", Entrega.fallo("550 no such user"));
        Map<String, List<BajaReclamada>> envios = new LinkedHashMap<>();
        envios.put("usuario@example.com", List.of(baja("Uno")));
        envios.put("otro@example.com", List.of(baja("Dos")));

        Map<String, Entrega> resultado = new DespachadorPorMail(plantilla, mail).despachar(envios);

        assertThat(resultado.get("usuario@example.com").enviada()).isTrue();
        assertThat(resultado.get("otro@example.com").error()).isEqualTo("550 no such user");
    }

    @Test
    @DisplayName("the message key depends only on the recipient and the set of rows, not on their order")
    void claveEstablePorReclamo() {
        List<CorreoDirigido> vistos = new ArrayList<>();
        MailPort mail = correos -> {
            vistos.addAll(correos);
            return Map.of();
        };
        UUID usuario = UUID.randomUUID();
        BajaReclamada a = new BajaReclamada(7, usuario, "usuario@example.com", "https://s.test/a", "A", "Sitio", 100, 80);
        BajaReclamada b = new BajaReclamada(3, usuario, "usuario@example.com", "https://s.test/b", "B", "Sitio", 100, 80);
        BajaReclamada c = new BajaReclamada(9, usuario, "usuario@example.com", "https://s.test/c", "C", "Sitio", 100, 80);
        DespachadorPorMail despachador = new DespachadorPorMail(plantilla, mail);

        despachador.despachar(Map.of("usuario@example.com", List.of(a, b)));
        despachador.despachar(Map.of("usuario@example.com", List.of(b, a)));
        despachador.despachar(Map.of("usuario@example.com", List.of(a, b, c)));

        assertThat(vistos.get(0).claveMensaje()).isNotBlank().matches("[0-9a-f]+")
                .isEqualTo(vistos.get(1).claveMensaje()).isNotEqualTo(vistos.get(2).claveMensaje());
    }

    @Test
    @DisplayName("two recipients with the same row ids still get different keys")
    void claveDependeDelUsuario() {
        List<CorreoDirigido> vistos = new ArrayList<>();
        MailPort mail = correos -> {
            vistos.addAll(correos);
            return Map.of();
        };
        BajaReclamada uno = new BajaReclamada(1, UUID.randomUUID(), "uno@example.com", "https://s.test/a", "A", "Sitio", 100, 80);
        BajaReclamada dos = new BajaReclamada(1, UUID.randomUUID(), "dos@example.com", "https://s.test/a", "A", "Sitio", 100, 80);

        new DespachadorPorMail(plantilla, mail).despachar(Map.of("uno@example.com", List.of(uno), "dos@example.com", List.of(dos)));

        assertThat(vistos).extracting(CorreoDirigido::claveMensaje).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every mail carries its recipient's own unsubscribe link, in the body and as a header value")
    void enlaceDeBajaPorDestinatario() {
        List<CorreoDirigido> vistos = new ArrayList<>();
        MailPort mail = correos -> {
            vistos.addAll(correos);
            return Map.of();
        };
        TokenBajaAvisos tokens = new TokenBajaAvisos("un-secreto-de-prueba-de-al-menos-32-bytes!!");
        EnlaceBaja enlaces = new EnlaceBaja(tokens, "https://api.example.com/");
        UUID uno = UUID.randomUUID();
        UUID dos = UUID.randomUUID();
        Map<String, List<BajaReclamada>> envios = new LinkedHashMap<>();
        envios.put("uno@example.com", List.of(new BajaReclamada(1, uno, "uno@example.com", "https://s.test/a", "A", "Sitio", 100, 80)));
        envios.put("dos@example.com", List.of(new BajaReclamada(2, dos, "dos@example.com", "https://s.test/b", "B", "Sitio", 100, 80)));

        new DespachadorPorMail(plantilla, mail, enlaces).despachar(envios);

        assertThat(vistos.get(0).enlaceBaja()).isEqualTo("https://api.example.com/api/notificaciones/baja/" + tokens.emitir(uno));
        assertThat(vistos.get(1).enlaceBaja()).isEqualTo("https://api.example.com/api/notificaciones/baja/" + tokens.emitir(dos));
        assertThat(vistos.get(0).correo().html()).contains(vistos.get(0).enlaceBaja());
        assertThat(vistos.get(0).correo().texto()).contains(vistos.get(0).enlaceBaja());
    }
}
