package ar.scraper.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback controller: forwards GET requests for client-side routes
 * (e.g. /picks, /favoritos, /splash) to index.html so React Router can
 * take over rendering. All current React Router routes are single
 * path segments, so a single-segment, non-dotted pattern is enough:
 * /api/(more) and /assets/(more) have 2+ segments and are never matched here
 * (Spring's PathPatternParser does not allow extra segments after a
 * wildcard pattern element, so a multi-segment catch-all is intentionally
 * avoided).
 * Asset paths (containing a dot, e.g. /assets/index-abc123.js) fall
 * through to Spring Boot's static resource handler.
 */
@Controller
public class SpaController {

    @GetMapping(value = { "/", "/{path:[^.]*}" })
    public String spaFallback() {
        return "forward:/index.html";
    }
}
