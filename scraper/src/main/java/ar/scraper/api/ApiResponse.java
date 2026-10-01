package ar.scraper.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private T data;
    private PageMeta page;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<List<T>> page(List<T> items, PageMeta page) {
        return new ApiResponse<>(items, page);
    }
}
