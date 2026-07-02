package ar.scraper.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaController()).build();
    }

    // ── New behavior: 2-segment /picks/{categoria} must forward to index.html ──

    @Test
    void picksCategoriaTwoSegmentPathForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/picks/musculosas"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void picksCategoriaTwoSegmentPathForwardsToIndexHtmlForDifferentSlug() throws Exception {
        mockMvc.perform(get("/picks/remeras"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    // ── Existing behavior must not regress ──────────────────────────────────

    @Test
    void picksSingleSegmentPathStillForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/picks"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void dottedAssetPathIsNotForwarded() throws Exception {
        mockMvc.perform(get("/nonexistent.js"))
                .andExpect(status().isNotFound());
    }
}
