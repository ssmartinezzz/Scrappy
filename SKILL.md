# Fashion Scraper — Índice de Documentación Técnica

Este índice referencia todos los documentos técnicos del proyecto. Cada doc es independiente y cubre un área específica.

---

## Documentos disponibles

| Doc | Qué cubre | Cuándo leerlo |
|-----|-----------|---------------|
| [`CLAUDE.md`](./CLAUDE.md) | Estado completo del proyecto, stack, sitios, API, problemas conocidos | Siempre — inicio de sesión |
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | Decisiones de arquitectura y por qué se tomaron | Antes de proponer cambios estructurales |
| [`docs/ADD_SCRAPER.md`](./docs/ADD_SCRAPER.md) | Paso a paso para agregar un sitio nuevo | Al agregar soporte para una tienda nueva |
| [`docs/ML_PIPELINE.md`](./docs/ML_PIPELINE.md) | Cómo funciona el pipeline ML y cómo extenderlo | Al modificar scoring, badges o clustering |
| [`docs/API_REFERENCE.md`](./docs/API_REFERENCE.md) | Todos los endpoints REST con params y responses | Al modificar la API o integrar con externos |

---

## Convenciones del proyecto

### Nombrado de versiones
Los zips se entregan como `fashion-scraper-vN[letter].zip`. Hotfixes de compilación usan letra (`v18b`). Features nuevas incrementan el número (`v19`).

### Spec Driven Development
Antes de codear cualquier feature:
1. Escribir la spec en chat (qué cambia, qué NO cambia, decisiones a confirmar)
2. Usuario aprueba
3. Implementar en orden: model → service → api → frontend

### Escaping en Java strings con JS embebido
**Regla crítica**: dentro de strings Java que contienen código JavaScript:
- Regex con `\d`, `\s`, etc. → usar `\\d`, `\\s` (un nivel más de escape)
- Comillas dobles en JS → usar `'` (single quotes) siempre que sea posible
- NO usar `\?`, `\$`, `\,`, `\.` como escapes en Java — son ilegales
- Verificar siempre con el script Python de validación antes de empaquetar

### Patrón de adición de sitio nuevo
Ver `docs/ADD_SCRAPER.md` — siempre 4 archivos a tocar: `config.properties`, `ScraperFactory`, y opcionalmente un nuevo `*Page.java` + `*Scraper.java`.
