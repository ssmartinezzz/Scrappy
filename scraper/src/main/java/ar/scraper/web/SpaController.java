package ar.scraper.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback controller: forwards GET requests for client-side routes
 * (e.g. /picks, /favoritos, /splash) to index.html so React Router can
 * take over rendering. Almost all current React Router routes are single
 * path segments, so a single-segment, non-dotted pattern covers them:
 * /api/(more) and /assets/(more) have 2+ segments and are never matched here
 * (Spring's PathPatternParser does not allow extra segments after a
 * wildcard pattern element, so a general multi-segment catch-all is
 * intentionally avoided).
 * Asset paths (containing a dot, e.g. /assets/index-abc123.js) fall
 * through to Spring Boot's static resource handler.
 *
 * Intentional single exception: /picks/{categoria} is a nested, deep-linkable
 * client route (per-category picks page), so it gets its own narrow mapping
 * with a literal "/picks/" prefix. This never shadows /api/** (RestController
 * mappings win regardless) or any other route, and keeps the general
 * catch-all narrow — broaden only when a second nested route lands.
 */
@Controller
public class SpaController {

    @GetMapping(value = { "/", "/{path:[^.]*}", "/picks/{categoria:[^.]*}" })
    public String spaFallback() {
        return "forward:/index.html";
    }
}
