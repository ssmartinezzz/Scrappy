import { useEffect, useMemo, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Pill, PackageSearch } from 'lucide-react';
import { fetchSuplementosBuilder, fetchSuplementosTipos, fmt } from '../api';
import { MultiSelectTags } from './ui/multi-select-tags';
import { MoneyInput } from './ui/money-input';
import { cn } from '@/lib/utils';

// The type list comes from GET /api/suplementos/tipos. It used to be hard-coded here,
// which meant every new backend subtype had to be added in two places — and a forgotten
// edit left a type the builder returns and this selector could not offer.
const DEFAULT_TIPOS = new Set(['Proteína en Polvo', 'Creatina', 'Magnesio']);

/**
 * Groups the fetched types for MultiSelectTags, preserving server order and mapping the
 * `grupo: null` bucket to "Otros". Group headings are discovered from the data rather
 * than listed here, so a new backend group needs no change in this file.
 */
function buildGroups(tipos) {
  const orden = [];
  for (const t of tipos) {
    const grupo = t.grupo ?? null;
    if (!orden.some(g => g.grupo === grupo)) orden.push({ grupo, tags: [] });
    orden.find(g => g.grupo === grupo).tags.push(t.tipo);
  }
  // "Otros" last: it is the catch-all, so it reads as the tail of the list even when the
  // first ungrouped type happens to arrive early in combo order.
  orden.sort((a, b) => (a.grupo === null ? 1 : 0) - (b.grupo === null ? 1 : 0));
  return orden.map(g => ({ label: g.grupo ?? 'Otros', tags: g.tags }));
}

export default function SuplementosPanel() {
  const [tiposDisponibles, setTiposDisponibles] = useState([]);
  const [tipos, setTipos] = useState(DEFAULT_TIPOS);
  const [presupuesto, setPresupuesto] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [picks, setPicks] = useState(null);
  const [sinStock, setSinStock] = useState([]);
  // URLs ya mostradas, AGRUPADAS POR SUBTIPO. El pick del servidor es determinístico
  // a propósito, así que "Regenerar" tiene que decir qué vio para recibir el
  // siguiente — sin esto la petición es idéntica y la respuesta también.
  // El agrupado importa porque el servidor recicla el pool de a un subtipo: ver
  // el reinicio en `generar`.
  const [vistos, setVistos] = useState({});
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    let vigente = true;
    fetchSuplementosTipos()
      .then(lista => {
        if (!vigente) return;
        setTiposDisponibles(lista);
        // Keep only defaults the server actually offers, so a renamed or retired subtype
        // cannot leave a selection that the builder would then report as "sin stock".
        const ofrecidos = new Set(lista.map(t => t.tipo));
        setTipos(prev => new Set([...prev].filter(t => ofrecidos.has(t))));
      })
      .catch(() => { /* fetchSuplementosTipos ya devuelve [] ante error */ });
    return () => { vigente = false; };
  }, []);

  const groups = useMemo(() => buildGroups(tiposDisponibles), [tiposDisponibles]);

  function toggleTipo(tipo) {
    setTipos(prev => {
      const next = new Set(prev);
      if (next.has(tipo)) next.delete(tipo);
      else next.add(tipo);
      return next;
    });
  }

  /**
   * @param {boolean} acumulando  true desde "Regenerar": arrastra lo ya visto para
   *   pedir el siguiente candidato. false desde "Armar": consulta nueva, se empieza
   *   de cero para que el primer resultado sea siempre el mejor del catálogo.
   */
  async function generar(acumulando = false) {
    if (tipos.size === 0) {
      setError('Seleccioná al menos un tipo de suplemento.');
      return;
    }
    setLoading(true);
    setError(null);
    const previos = acumulando ? vistos : {};
    const excluir = Object.values(previos).flat();
    try {
      const data = await fetchSuplementosBuilder({
        tipos: Array.from(tipos),
        presupuesto: presupuesto ? Number(presupuesto) : 0,
        excluir,
      });
      const nuevos = data?.picks ?? [];
      setPicks(nuevos);
      setSinStock(data?.sinStock ?? []);
      // El servidor recicla el pool de UN subtipo cuando ese subtipo se queda sin
      // candidatos frescos, así que el reinicio es por subtipo. Reiniciar todo ante
      // cualquier repetición era el bug: un subtipo con un único candidato se repite
      // en cada respuesta y borraba el historial de los demás, dejando a la proteína
      // alternando para siempre entre sus dos primeros candidatos.
      const siguientes = { ...previos };
      for (const p of nuevos) {
        if (!p.url) continue;
        const yaVistos = siguientes[p.tipo] ?? [];
        siguientes[p.tipo] = yaVistos.includes(p.url) ? [p.url] : [...yaVistos, p.url];
      }
      setVistos(siguientes);
    } catch {
      setError('Error al conectar con el servidor.');
    } finally {
      setLoading(false);
    }
  }

  const disabled = loading || tipos.size === 0;

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-[920px] px-[20px] py-[24px]">
        <p className="mb-[6px] text-eyebrow uppercase text-t3">Armador</p>
        <h1 className="mb-[24px] text-display-2 text-t1">Stack de suplementos</h1>

        {/* Type selector */}
        <div className="mb-[16px] rounded-card bg-s1 px-[24px] py-[20px]">
          <p className="mb-[14px] text-[.85rem] font-semibold text-t2">
            ¿Qué suplementos necesitás?
          </p>
          <MultiSelectTags groups={groups} selected={tipos} onToggle={toggleTipo} />
        </div>

        {/* Budget + generate */}
        <div className="mb-[24px] flex flex-wrap items-end gap-[12px]">
          <div className="min-w-[200px] flex-1">
            <label
              htmlFor="presupuesto"
              className="mb-[6px] block text-[.8rem] font-semibold text-t3"
            >
              Presupuesto total (opcional)
            </label>
            <MoneyInput
              id="presupuesto"
              value={presupuesto}
              onChange={setPresupuesto}
              placeholder="Ej: 50.000"
            />
          </div>
          <button
            onClick={() => generar(false)}
            disabled={disabled}
            className={cn(
              'inline-flex min-h-[44px] shrink-0 items-center whitespace-nowrap rounded-btn px-[28px]',
              'text-[.9rem] font-bold transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
              disabled
                ? 'cursor-not-allowed bg-s3 text-t3'
                : 'cursor-pointer bg-primary text-white hover:bg-primary2'
            )}
          >
            {loading ? 'Buscando...' : 'Generar'}
          </button>
        </div>

        {/* Error */}
        {error && (
          <div
            role="alert"
            className="mb-[20px] rounded-btn border border-warning bg-s1 px-[18px] py-[14px] text-[.88rem] text-warning"
          >
            {error}
          </div>
        )}

        {/* Empty state — nothing found at all */}
        {picks !== null && picks.length === 0 && sinStock.length === 0 && (
          <div className="px-[20px] py-[48px] text-center text-[.95rem] text-t3">
            No se encontraron suplementos para los tipos seleccionados. Probá corriendo un scraping primero.
          </div>
        )}

        {/* Results */}
        {picks !== null && (picks.length > 0 || sinStock.length > 0) && (
          <>
            {picks.length > 0 && (
              <div className="mb-[16px] flex items-baseline justify-end gap-[8px]">
                <span className="text-[.8rem] font-semibold text-t3">Total stack</span>
                <span className="text-[1.3rem] font-extrabold tabular-nums text-primary" aria-live="polite">
                  ${fmt(picks.reduce((acc, p) => acc + p.precio, 0))}
                </span>
              </div>
            )}

            <div className="mb-[24px] grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-[16px]">
              {picks.map((pick, i) => (
                <motion.a
                  key={i}
                  href={pick.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="block no-underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
                  initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={
                    reduceMotion
                      ? { duration: 0 }
                      : { duration: 0.22, delay: Math.min(i, 8) * 0.03, ease: 'easeOut' }
                  }
                >
                  <div className="h-full overflow-hidden rounded-card border-[1.5px] border-s2 bg-s1 transition-shadow hover:shadow-[0_10px_30px_rgba(0,0,0,0.12)]">
                    <div className="relative h-[160px] overflow-hidden bg-s2">
                      {pick.img ? (
                        <img
                          src={pick.img}
                          alt={pick.nombre}
                          loading="lazy"
                          className="h-full w-full object-cover"
                          onError={e => { e.currentTarget.style.display = 'none'; }}
                        />
                      ) : (
                        <div className="flex h-full w-full items-center justify-center text-t4">
                          <Pill size={38} aria-hidden="true" />
                        </div>
                      )}
                      <span className="absolute left-[8px] top-[8px] rounded-full bg-primary px-[9px] py-[3px] text-[.68rem] font-bold uppercase tracking-[.07em] text-white">
                        {pick.tipo}
                      </span>
                    </div>
                    <div className="px-[16px] pb-[18px] pt-[14px]">
                      <p className="mb-[4px] line-clamp-2 text-[.88rem] font-bold leading-[1.35] text-t1">
                        {pick.nombre}
                      </p>
                      <p className="mb-[10px] text-[.76rem] text-t3">
                        {pick.marca && pick.marca !== pick.sitio ? `${pick.marca} · ` : ''}
                        {pick.sitio}
                      </p>
                      <p className="text-[1.05rem] font-extrabold tabular-nums text-primary">
                        ${fmt(pick.precio)}
                      </p>
                    </div>
                  </div>
                </motion.a>
              ))}

              {/* Sin stock cards */}
              {sinStock.map(tipo => (
                <div
                  key={tipo}
                  className="flex min-h-[200px] flex-col items-center justify-center gap-[8px] rounded-card border-[1.5px] border-dashed border-s3 bg-s1 p-[20px] opacity-60"
                >
                  <PackageSearch size={26} className="text-t4" aria-hidden="true" />
                  <span className="rounded-full bg-s3 px-[9px] py-[3px] text-[.68rem] font-bold uppercase tracking-[.07em] text-t3">
                    {tipo}
                  </span>
                  <span className="text-center text-[.78rem] text-t4">
                    Sin stock en tu catálogo
                  </span>
                </div>
              ))}
            </div>

            {/* Regenerate */}
            <div className="text-center">
              <button
                onClick={() => generar(true)}
                disabled={loading}
                className={cn(
                  'inline-flex min-h-[44px] items-center rounded-btn border-[1.5px] border-primary bg-transparent px-[26px]',
                  'text-[.88rem] font-semibold text-primary transition-colors',
                  'hover:bg-primary hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
                  loading ? 'cursor-not-allowed opacity-60 hover:bg-transparent hover:text-primary' : 'cursor-pointer'
                )}
              >
                {loading ? 'Buscando...' : 'Regenerar'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
