package ar.scraper.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Pagination block of an {@link ApiResponse}. {@code number} is 0-based. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PageMeta {
    private int number;
    private int size;
    private long total;
    private int totalPages;

    public static PageMeta of(int number, int size, long total) {
        int pages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageMeta(number, size, total, pages);
    }
}
