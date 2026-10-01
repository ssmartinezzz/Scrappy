# Sonar JS issues

**Objetivo**: cerrar los 287 issues `javascript:*` abiertos en SonarCloud
(`ssmartinezzz_Scrappy`, master `a59cef2`) en un solo PR, sin cambiar lo que
el usuario ve salvo donde el issue *es* el defecto (teclado, errores de red).

**Problema**: 41 promesas sin rama de rechazo (S9383) terminan en
`unhandledrejection` y dejan la UI con datos viejos sin avisar; 23 elementos
clickeables no se pueden usar con teclado (S1082). El resto son smells.

**Fuera de alcance**: `authSession.js:46` (S2245) — falso positivo aceptado:
`crypto.randomUUID()` exige contexto seguro y rompe LAN por HTTP.

**Constraints**
- TDD estricto (sesión). Runner: `cd frontend && npx vitest run`.
  Cambio de comportamiento (T1, T2) → test rojo observado antes del arreglo.
  Refactor (T3, T5, T6) → los tests existentes pasan **sin editar** (CODE-2).
- Cada task: `npx vitest run` verde + `VITE_API_BASE_URL=http://localhost:3000 npm run build` OK.
- Un commit por task (COMMIT-4), conventional, sin atribución de IA.
- Un `<div onClick>` pasa a `<button type="button">` con estilos reseteados
  para que el layout no cambie; nunca `role="button"` sobre un div (S6848).
- Fuente de verdad del inventario: `GET /api/issues/search?componentKeys=ssmartinezzz_Scrappy&languages=js&resolved=false`.

## Tareas

- [ ] **T1** Promesas flotantes — S9383 (41)
- [ ] **T2** Accesibilidad — S1082, S6848, S6819, S6772, S6853, S6845, S6847, S6850, S9379 (55)
- [ ] **T3** React — S6478, S6479, S6481 (32)
- [ ] **T4** Tests — S9020, S9027, S7763 en tests (42)
- [ ] **T5** Legibilidad — S3358, S3776, S4624 (47)
- [ ] **T6** Limpiezas mecánicas — resto de reglas (~69)
- [ ] **T7** Verificación final: suite + build + e2e browser + re-análisis Sonar del PR

## Criterios de aceptación

- El análisis de Sonar sobre el PR no deja issues JS abiertos salvo S2245
  y los que se registren abajo como no arreglables, con motivo.
- Suite vitest y build verdes; e2e browser sin regresiones contra master.

## Progreso y evidencia

(se completa por task)

## Próximo paso

T1.
