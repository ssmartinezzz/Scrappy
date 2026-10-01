package ar.scraper.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {
    private Detail error;

    public static ApiError of(String code, String message) {
        return new ApiError(new Detail(code, message, null));
    }

    public static ApiError of(String code, String message, Object details) {
        return new ApiError(new Detail(code, message, details));
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Detail {
        private String code;
        private String message;
        /**
         * Optional machine-readable context (e.g. the current state on a stale-write conflict).
         */
        private Object details;
    }
}
