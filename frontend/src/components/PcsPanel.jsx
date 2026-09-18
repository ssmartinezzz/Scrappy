import { useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Cpu, PackageSearch, Unplug } from 'lucide-react';
import { fetchPcsBuilder, fmt } from '../api';
import { MoneyInput } from './ui/money-input';
import { cn } from '@/lib/utils';

const SLOT_LABELS = {
  mother: 'Motherboard',
  cpu: 'CPU',
  ram: 'RAM',
  gabinete: 'Gabinete',
  fuente: 'Fuente',
  gpu: 'Placa de video',
  almacenamiento: 'Almacenamiento',
};

function slotLabel(slot) {
  return SLOT_LABELS[slot] ?? slot;
}

/** Une solo los campos con dato — un clasificador que se abstiene deja "" / 0. */
function resumenSpecs(specs) {
  if (!specs) return '';
  const partes = [];
  if (specs.socket) partes.push(specs.socket);
  if (specs.ddr) partes.push(specs.ddr);
  if (specs.formFactor) partes.push(specs.formFactor);
  if (specs.watts) partes.push(`${specs.watts} W`);
  if (specs.capacidadGb) partes.push(`${specs.capacidadGb} GB`);
  if (specs.tipoMemoria) partes.push(specs.tipoMemoria);
  return partes.join(' · ');
}

export default function PcsPanel() {
  const [presupuesto, setPresupuesto] = useState('');
  const [conGpu, setConGpu] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [data, setData] = useState(null);
  // URLs ya mostradas, agrupadas por SLOT. El pick del servidor es determinístico
  // a propósito, así que "Regenerar" tiene que decir qué vio para recibir el
  // siguiente — mismo contrato que SuplementosPanel, pero por slot en vez de tipo.
  const [vistos, setVistos] = useState({});
  const reduceMotion = useReducedMotion();

  /**
   * @param {boolean} acumulando  true desde "Regenerar": arrastra lo ya visto para
   *   pedir el siguiente candidato. false desde "Generar": consulta nueva, se
   *   empieza de cero para que el primer resultado sea siempre el mejor del catálogo.
   */
  async function generar(acumulando = false) {
    setLoading(true);
    setError(null);
    const previos = acumulando ? vistos : {};
    const excluir = Object.values(previos).flat();
    try {
      const resp = await fetchPcsBuilder({
        presupuesto: presupuesto ? Number(presupuesto) : 0,
        conGpu,
        excluir,
      });
      setData(resp);
      const nuevos = resp?.picks ?? [];
      // El servidor recicla el pool de UN slot cuando ese slot se queda sin
      // candidatos frescos, así que el reinicio es por slot — no global.
      const siguientes = { ...previos };
      for (const p of nuevos) {
        if (!p.url) continue;
        const yaVistos = siguientes[p.slot] ?? [];
        siguientes[p.slot] = yaVistos.includes(p.url) ? [p.url] : [...yaVistos, p.url];
      }
      setVistos(siguientes);
    } catch {
      setError('Error al conectar con el servidor.');
    } finally {
      setLoading(false);
    }
  }

  const picks = data?.picks ?? [];
  const sinStock = data?.sinStock ?? [];
  const sinCompatible = data?.sinCompatible ?? [];

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-[920px] px-[20px] py-[24px]">
        <p className="mb-[6px] text-eyebrow uppercase text-t3">Armador</p>
        <h1 className="mb-[24px] text-display-2 text-t1">Armador de PCs</h1>

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
              placeholder="Ej: 800.000"
            />
          </div>
          <label className="flex min-h-[44px] cursor-pointer items-center gap-[8px] text-[.85rem] text-t2">
            <input
              type="checkbox"
              checked={conGpu}
              onChange={e => setConGpu(e.target.checked)}
              className="h-[18px] w-[18px] cursor-pointer accent-primary"
            />
            Incluir placa de video
          </label>
          <button
            onClick={() => generar(false)}
            disabled={loading}
            className={cn(
              'inline-flex min-h-[44px] shrink-0 items-center whitespace-nowrap rounded-btn px-[28px]',
              'text-[.9rem] font-bold transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
              loading
                ? 'cursor-not-allowed bg-s3 text-t3'
                : 'cursor-pointer bg-primary text-white hover:bg-primary2'
            )}
          >
            {loading ? 'Buscando...' : 'Generar'}
          </button>
        </div>

        {error && (
          <div
            role="alert"
            className="mb-[20px] rounded-btn border border-warning bg-s1 px-[18px] py-[14px] text-[.88rem] text-warning"
          >
            {error}
          </div>
        )}

        {data !== null && picks.length === 0 && sinStock.length === 0 && sinCompatible.length === 0 && (
          <div className="px-[20px] py-[48px] text-center text-[.95rem] text-t3">
            No se encontró ningún componente. Probá corriendo un scraping primero.
          </div>
        )}

        {data !== null && (picks.length > 0 || sinStock.length > 0 || sinCompatible.length > 0) && (
          <>
            {picks.length > 0 && (
              <div className="mb-[16px] flex items-baseline justify-end gap-[8px]">
                <span className="text-[.8rem] font-semibold text-t3">Total estimado</span>
                <span className="text-[1.3rem] font-extrabold tabular-nums text-primary" aria-live="polite">
                  ${fmt(data.totalEstimado)}
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
                          <Cpu size={38} aria-hidden="true" />
                        </div>
                      )}
                      <span className="absolute left-[8px] top-[8px] rounded-full bg-primary px-[9px] py-[3px] text-[.68rem] font-bold uppercase tracking-[.07em] text-white">
                        {slotLabel(pick.slot)}
                      </span>
                    </div>
                    <div className="px-[16px] pb-[18px] pt-[14px]">
                      <p className="mb-[4px] line-clamp-2 text-[.88rem] font-bold leading-[1.35] text-t1">
                        {pick.nombre}
                      </p>
                      <p className="mb-[6px] text-[.76rem] text-t3">
                        {pick.marca && pick.marca !== pick.sitio ? `${pick.marca} · ` : ''}
                        {pick.sitio}
                      </p>
                      {resumenSpecs(pick.specs) && (
                        <p className="mb-[10px] text-[.72rem] text-t4">
                          {resumenSpecs(pick.specs)}
                        </p>
                      )}
                      <p className="text-[1.05rem] font-extrabold tabular-nums text-primary">
                        ${fmt(pick.precio)}
                      </p>
                    </div>
                  </div>
                </motion.a>
              ))}

              {sinStock.map(slot => (
                <div
                  key={`sin-stock-${slot}`}
                  className="flex min-h-[200px] flex-col items-center justify-center gap-[8px] rounded-card border-[1.5px] border-dashed border-s3 bg-s1 p-[20px] opacity-60"
                >
                  <PackageSearch size={26} className="text-t4" aria-hidden="true" />
                  <span className="rounded-full bg-s3 px-[9px] py-[3px] text-[.68rem] font-bold uppercase tracking-[.07em] text-t3">
                    {slotLabel(slot)}
                  </span>
                  <span className="text-center text-[.78rem] text-t4">
                    Sin stock
                  </span>
                </div>
              ))}

              {sinCompatible.map(slot => (
                <div
                  key={`sin-compatible-${slot}`}
                  className="flex min-h-[200px] flex-col items-center justify-center gap-[8px] rounded-card border-[1.5px] border-dashed border-s3 bg-s1 p-[20px] opacity-60"
                >
                  <Unplug size={26} className="text-t4" aria-hidden="true" />
                  <span className="rounded-full bg-s3 px-[9px] py-[3px] text-[.68rem] font-bold uppercase tracking-[.07em] text-t3">
                    {slotLabel(slot)}
                  </span>
                  <span className="text-center text-[.78rem] text-t4">
                    Sin compatible
                  </span>
                  <span className="text-center text-[.7rem] text-t4">
                    ninguna opción compatible con la mother elegida
                  </span>
                </div>
              ))}
            </div>

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
