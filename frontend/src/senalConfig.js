// Back-compat re-export — the canonical source moved to lib/colors.js
// (design "Decision: Badge/semantic color single source of truth"). Keeping
// this file/path/shape intact so any existing import of `senalConfig.js`
// keeps resolving without edits.
export { SEÑAL_CONFIG, SEÑALES_CONFIABLES, scoreColor } from './lib/colors';
