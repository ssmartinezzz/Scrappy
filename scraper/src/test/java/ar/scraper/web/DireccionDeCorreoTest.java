package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.db.UsuarioRepository.ResultadoEmail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DireccionDeCorreo — what may be stored as the reset channel")
class DireccionDeCorreoTest {

    @Test
    @DisplayName("trims and lowercases, because chk_usuario_email_lower rejects anything else")
    void normaliza() {
        assertThat(DireccionDeCorreo.normalizar("  Titular@Example.COM ")).isEqualTo("titular@example.com");
    }

    @Test
    @DisplayName("null and blank mean clear the address")
    void vacioLimpia() {
        assertThat(DireccionDeCorreo.normalizar(null)).isNull();
        assertThat(DireccionDeCorreo.normalizar("   ")).isNull();
    }

    @Test
    @DisplayName("a malformed, oversized or non-string value is 400 email_invalido")
    void invalida() {
        for (Object malo : new Object[]{"sin-arroba", "dos@@example.com", "a b@example.com", "x@sinpunto",
                "@example.com", "x@", 42, "a".repeat(250) + "@example.com"}) {
            assertThatThrownBy(() -> DireccionDeCorreo.normalizar(malo))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.code()).isEqualTo("email_invalido");
                    });
        }
    }

    @Test
    @DisplayName("an address that is a header injection, a list, a display form or not plain ASCII is 400 email_invalido")
    void rechazaLoQueNoEsUnaSolaDireccion() {
        for (String malo : new String[]{"a@b.com\r\nBcc: z@evil.com", "a@b.com\nx", "a@b.com, c@d.com", "a,b@x.com",
                "a;b@x.com", "<a@x.com>", "a@x.com>", "\"a\"@x.com", "a@x.com\u2028", "a\u0000b@x.com", "ñandú@x.com",
                "a@x..com", "a@-x.com", "a@x-.com", "a@.com", "a@x.com.", ".a@x.com", "a.@x.com", "a..b@x.com",
                "a(b)@x.com", "a@[127.0.0.1]", "a".repeat(65) + "@x.com", "a@" + "b".repeat(64) + ".com",
                "a".repeat(300) + "@x.com"}) {
            assertThatThrownBy(() -> DireccionDeCorreo.normalizar(malo)).as(malo)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("email_invalido"));
        }
    }

    @Test
    @DisplayName("ordinary addresses, plus tags and apostrophes, still pass")
    void aceptaDireccionesComunes() {
        for (String bueno : new String[]{"o'brien+tag@sub.example.co.ar", "a_b-c.d@x-y.com", "a@b.cc", "a1@x1.com"}) {
            assertThat(DireccionDeCorreo.normalizar(bueno)).isEqualTo(bueno);
        }
    }

    @Test
    @DisplayName("lowercasing ignores the JVM default locale (Turkish dotless i)")
    void minusculasSinLocale() {
        java.util.Locale previo = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(DireccionDeCorreo.normalizar("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            java.util.Locale.setDefault(previo);
        }
    }

    @Test
    @DisplayName("each non-success result maps to its own status and code; success passes")
    void resultados() {
        DireccionDeCorreo.exigirCambiado(ResultadoEmail.CAMBIADO);
        assertRechazo(ResultadoEmail.TOMADO, HttpStatus.CONFLICT, "email_no_disponible");
        assertRechazo(ResultadoEmail.ES_SERVICIO, HttpStatus.CONFLICT, "cuenta_de_servicio");
        assertRechazo(ResultadoEmail.NO_EXISTE, HttpStatus.NOT_FOUND, "no_existe");
    }

    private static void assertRechazo(ResultadoEmail r, HttpStatus status, String codigo) {
        assertThatThrownBy(() -> DireccionDeCorreo.exigirCambiado(r))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(status);
                    assertThat(e.code()).isEqualTo(codigo);
                });
    }
}
