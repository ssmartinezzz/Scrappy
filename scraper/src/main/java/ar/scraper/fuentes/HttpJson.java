package ar.scraper.fuentes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared GET helper for the three index sources below. */
final class HttpJson {

    private HttpJson() {
    }

    static String get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "FashionScraper/1.0")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new java.io.IOException("HTTP " + response.statusCode() + " de " + url);
        }
        return response.body();
    }
}
