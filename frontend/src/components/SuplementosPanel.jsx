import { useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Pill, PackageSearch } from 'lucide-react';
import { fetchSuplementosBuilder, fmt } from '../api';
import { MultiSelectTags } from './ui/multi-select-tags';
import { MoneyInput } from './ui/money-input';
import { cn } from '@/lib/utils';

const TIPOS_DISPONIBLES = [
  { tipo: 'Proteína en Polvo', grupo: 'Proteína' },
  { tipo: 'Barra Proteica',    grupo: 'Proteína' },
  { tipo: 'Pancake / Waffle',  grupo: 'Proteína' },
  { tipo: 'Snack Proteico',    grupo: 'Proteína' },
  { tipo: 'Vitamina C',        grupo: 'Vitaminas' },
  { tipo: 'Multivitamínico',   grupo: 'Vitaminas' },
  { tipo: 'Vitamina D',        grupo: 'Vitaminas' },
  { tipo: 'Omega 3',           grupo: 'Vitaminas' },
  { tipo: 'Complejo B',        grupo: 'Vitaminas' },
  { tipo: 'Zinc',              grupo: 'Vitaminas' },
  { tipo: 'Mayonesa',          grupo: 'Aderezos' },
  { tipo: 'Ketchup / Salsa',  grupo: 'Aderezos' },
  { tipo: 'Mostaza',          grupo: 'Aderezos' },
  { tipo: 'Maple / Sirope',   grupo: 'Aderezos' },
  { tipo: 'Creatina',         grupo: null },
  { tipo: 'Magnesio',         grupo: null },
  { tipo: 'Quemador',         grupo: null },
];
const DEFAULT_TIPOS = new Set(['Proteína en Polvo', 'Creatina', 'Magnesio']);

// Grouped tag data for MultiSelectTags, derived from TIPOS_DISPONIBLES —
// preserves order and maps the `grupo: null` bucket to "Otros".
const GROUPS = ['Proteína', 'Vitaminas', 'Aderezos', null].map(grupo => ({
  label: grupo ?? 'Otros',
  tags: TIPOS_DISPONIBLES.filter(t => t.grupo === grupo).map(t => t.tipo),
}));

export default function SuplementosPanel() {
  const [tipos, setTipos] = useState(DEFAULT_TIPOS);
  const [presupuesto, setPresupuesto] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [picks, setPicks] = useState(null);
  const [sinStock, setSinStock] = useState([]);
  const reduceMotion = useReducedMotion();

  function toggleTipo(tipo) {
    setTipos(prev => {
      const next = new Set(prev);
      if (next.has(tipo)) next.delete(tipo);
      else next.add(tipo);
      return next;
    });
  }

  async function generar() {
    if (tipos.size === 0) {
      setError('Seleccioná al menos un tipo de suplemento.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await fetchSuplementosBuilder({
        tipos: Array.from(tipos),
        presupuesto: presupuesto ? Number(presupuesto) : 0,
      });
      setPicks(data?.picks ?? []);
      setSinStock(data?.sinStock ?? []);
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
          <MultiSelectTags groups={GROUPS} selected={tipos} onToggle={toggleTipo} />
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
            onClick={generar}
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
                onClick={generar}
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
