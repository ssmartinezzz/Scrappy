// Shared training-phase labels for the ML training UI (GpuTrainingOverlay +
// MlStatusPanel). Previously duplicated as two independent literals that had
// already drifted once (RELY-101) — kept as a single source of truth here.
export const PHASE_LABELS = {
  renormalizando: 'Actualizando catálogo...',
  starting:       'Iniciando...',
  text:           'Clasificador de texto',
  image_download: 'Descargando imágenes',
  image:          'EfficientNet-B3',
  training:       'Entrenando clasificador de texto',
  embedding:      'Construyendo índice visual (embeddings)',
  idle:           '',
  timeout:        'Timeout',
  error:          'Error',
};
