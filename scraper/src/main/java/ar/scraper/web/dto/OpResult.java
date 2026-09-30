package ar.scraper.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpResult {
    private boolean ok;
    private String mensaje;

    public static OpResult ok() {
        return new OpResult(true, null);
    }

    public static OpResult of(boolean ok, String mensaje) {
        return new OpResult(ok, mensaje);
    }
}
