# Proposal: GYMRAT UI mejorada + IPC señal de compra + ML transparency

## Intent

El sistema YA calcula tres cosas valiosas que el usuario no puede ver: la clasificación GYMRAT, la señal de compra ajustada por IPC (`/api/recomendacion` + `/api/inflacion`), y los badges del modelo ML (`/api/ml/estado`). Todo eso muere en el backend porque el dashboard no lo expone. El usuario no percibe que "la IA aprendió algo" ni que el sistema sabe si conviene comprar. Esta propuesta hace VISIBLE lo que ya existe y profundiza la categorización GYMRAT, sin tocar fuentes de datos ni agregar dependencias.

## Scope

### In Scope
- **GYMRAT**: ampliar `KW_TRAINING_ROPA` con vocabulario fitness/pesas argentino; derivar subetiqueta gym por prenda (Remera Gym, Short Gym, Calza Gym, Musculosa Gym, Buzo Gym) manteniendo `categoria` intacta; vista/sección filtrable GYMRAT con chips por subcategoría; badge visual "GYMRAT 🏋️" en cards.
- **IPC**: widget de inflación en header (mensual/anual + fecha de actualización); badge de señal de compra en cards con historial (🔥 mínimo histórico / ✅ buen momento / ⚠ esperar); tooltip explicando el razonamiento real; carga de `/api/recomendacion` al abrir detalle de producto.
- **ML transparency**: banner/pill de estado del modelo en header; badges ML con color por tipo en cada card; panel "¿Qué aprendió la IA?" en Tendencias; toast al finalizar entrenamiento; indicador de categoría refinada por ML vs rule-based.

### Out of Scope
- Cambiar las fuentes de IPC (argentinadatos.com + datos.gob.ar INDEC se mantienen — son confiables y actualizadas).
- Nuevos endpoints o tablas SQLite (se reusan los existentes).
- Reentrenar o cambiar los modelos ML (texto TF-IDF/LogReg, imagen torch).
- Nuevas dependencias frontend (todo en HTML/CSS/JS vanilla existente).
- Convertir GYMRAT en `categoria` o `rubro` propio (sigue siendo tag aditivo transversal).

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- `frontend`: nuevos widgets visibles (IPC header, señal de compra en cards, badges ML, banner estado modelo, panel "¿Qué aprendió la IA?", sección/filtro GYMRAT, toast de entrenamiento).
- `ml-pipeline`: exponer subetiqueta GYMRAT por prenda y vocabulario ampliado de training; nada cambia en scoring de precio.
- `api`: `/api/data` debe poder filtrar/devolver subetiqueta GYMRAT; respuestas de `/api/recomendacion`, `/api/inflacion`, `/api/ml/estado` consumidas tal cual (sin cambio de contrato salvo el campo de subetiqueta).

## Approach

- **GYMRAT**: en `NormalizerService.esGymrat()` ya existe el flag aditivo. Se amplía `KW_TRAINING_ROPA` y se computa una subetiqueta derivada cruzando `gymrat=true` con `categoria` (Remera→Remera Gym, etc.). El frontend agrupa por esa subetiqueta usando `/api/data?gymrat=true`.
- **IPC**: reutilizar `InflacionService` y `/api/recomendacion`. El frontend hace fetch del IPC al cargar el dashboard (header) y de la recomendación al abrir el detalle. La señal mapea a 3 estados visuales con tooltip.
- **ML**: el frontend consume `/api/ml/estado` para el banner y deriva los badges del `MlScore` ya presente en cada producto, pintándolos con un mapa color→badge. El panel de Tendencias agrega accuracy y distribución de badges.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `aggregator/NormalizerService.java` | Modified | Vocabulario `KW_TRAINING_ROPA` + subetiqueta GYMRAT |
| `resources/ml/ml_pipeline.py` | Modified | Exponer subetiqueta GYMRAT en output (si aplica) |
| `web/ApiController.java` | Modified | `/api/data` devuelve/filtra subetiqueta GYMRAT |
| `resources/static/index.html` | Modified | Widgets IPC, badges, banner ML, sección GYMRAT, toast |
| `web/InflacionService.java` | Unchanged | Fuentes IPC se mantienen |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Sobre-clasificación GYMRAT con keywords amplios | Med | Mantener guard de calzado/suplementos; revisar muestra real |
| `/api/recomendacion` lento al abrir detalle | Low | Cargar bajo demanda + cachear en cliente |
| Ruido visual por exceso de badges | Med | Jerarquía: máx 1-2 badges prominentes por card |

## Rollback Plan

Cambios aislados por archivo. Frontend: revertir `index.html` (sin build step). Backend: revertir `NormalizerService` y `ApiController`; el campo subetiqueta es aditivo, no rompe el contrato existente. Regenerar fat JAR con `mvn -f scraper/pom.xml clean package -DskipTests`.

## Dependencies

- Endpoints existentes: `/api/inflacion`, `/api/recomendacion`, `/api/ml/estado`, `/api/data`, `/api/tendencias`.

## Success Criteria

- [ ] El IPC actual y la señal de compra son visibles sin abrir el código.
- [ ] Las cards muestran badge GYMRAT y badges ML con color.
- [ ] Existe sección GYMRAT filtrable por subcategoría gym.
- [ ] El panel de Tendencias muestra qué aprendió el modelo (accuracy + distribución).
- [ ] No se agregaron dependencias ni se cambiaron las fuentes de IPC.
