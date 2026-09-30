package ar.scraper.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public final class UsuariosDtos {

    private UsuariosDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Usuario {
        private String id;
        private String username;
        private String email;
        private boolean activo;
        private boolean esServicio;
        private List<String> roles;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Creado {
        private boolean ok;
        private String id;
        private String username;
        private String role;
    }
}
