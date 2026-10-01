package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** The refresh token itself only ever travels in a cookie. */
public final class AuthDtos {

    private AuthDtos() {}

    /** {@code csrfNonce} is only present when a rotating session was opened. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Token {
        private String accessToken;
        private String tokenType;
        private long expiresIn;
        private String csrfNonce;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Me {
        private String username;
        private List<String> roles;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Logout {
        private boolean cerrada;
    }
}
