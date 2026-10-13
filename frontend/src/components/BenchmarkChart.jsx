import { cn } from '@/lib/utils';

// [slot, título, cómo se nombra en "Tu …"]
const SLOTS = [
  ['cpu', 'CPU', 'CPU'],
  ['gpu', 'Placa de video', 'placa de video'],
];

const ESCALAS = {
  gaming: "Índice gaming 1080p (Tom's Hardware) · Ryzen 7 9850X3D = 1000",
  blender: 'Blender, render multinúcleo (Open Data) · más es mejor',
  gpu: "1440p (Tom's Hardware) · RTX 4090 = 1000",
};

const GAMAS = { BAJA: 'económica', MEDIA: 'media', ALTA: 'alta' };

const PALABRAS = {
  ryzen: 'Ryzen', core: 'Core', ultra: 'Ultra', pro: 'PRO', rtx: 'RTX', gtx: 'GTX', rx: 'RX', arc: 'Arc',
  ti: 'Ti', super: 'Super', xt: 'XT', xtx: 'XTX', gre: 'GRE',
};

/** `core-i7-14700k` → `Core i7-14700K`; `rx-9070-xt` → `RX 9070 XT`. */
export function nombreModelo(clave) {
  const tokens = clave.split('-').map(t => PALABRAS[t] ?? (/\d/.test(t) && !/^i\d$/.test(t) ? t.toUpperCase() : t));
  if (tokens[0] === 'Core' && /^i\d$/.test(tokens[1])) {
    return `Core ${tokens[1]}-${tokens.slice(2).join(' ')}`;
  }
  return tokens.join(' ');
}

function Fila({ etiqueta, puntaje, max, destacada }) {
  return (
    <div className="flex flex-col gap-[4px]">
      <div className="flex items-baseline justify-between gap-[8px] text-[.78rem]">
        <span className={cn('min-w-0 truncate', destacada ? 'font-semibold text-t1' : 'text-t3')}>{etiqueta}</span>
        <span className="shrink-0 tabular-nums text-t2">{puntaje || 'sin benchmark'}</span>
      </div>
      {puntaje > 0 && (
        <div
          role="meter"
          aria-label={etiqueta}
          aria-valuenow={puntaje}
          aria-valuemin={0}
          aria-valuemax={max}
          className="h-[8px] w-full overflow-hidden rounded-full bg-s2"
        >
          <div
            className={cn('h-full rounded-full', destacada ? 'bg-primary' : 'bg-t4 opacity-60')}
            style={{ width: `${(puntaje / max) * 100}%` }}
          />
        </div>
      )}
    </div>
  );
}

function Slot({ slot, titulo, sustantivo, referencia }) {
  const { escala, pick, mejorDeGama, mejorDelCatalogo } = referencia;
  const filas = [
    { etiqueta: `Tu ${sustantivo}: ${nombreModelo(pick.modelo)}`, puntaje: pick.puntaje, destacada: true },
    mejorDeGama && {
      etiqueta: `Mejor de gama ${GAMAS[mejorDeGama.gama] ?? mejorDeGama.gama}: ${nombreModelo(mejorDeGama.modelo)}`,
      puntaje: mejorDeGama.puntaje,
    },
    mejorDelCatalogo && {
      etiqueta: `Mejor del catálogo: ${nombreModelo(mejorDelCatalogo.modelo)}`,
      puntaje: mejorDelCatalogo.puntaje,
    },
  ].filter(Boolean);
  const max = Math.max(...filas.map(f => f.puntaje));

  return (
    <div role="group" aria-labelledby={`benchmark-${slot}`} className="flex min-w-0 flex-col gap-[10px]">
      <div>
        <h3 id={`benchmark-${slot}`} className="text-[.85rem] font-bold text-t1">{titulo}</h3>
        <p className="text-[.7rem] text-t4">{ESCALAS[escala] ?? escala}</p>
      </div>
      {filas.map(f => <Fila key={f.etiqueta} max={max} {...f} />)}
    </div>
  );
}

export default function BenchmarkChart({ referencias }) {
  const presentes = SLOTS.filter(([slot]) => referencias?.[slot]);
  if (presentes.length === 0) return null;

  // Flex y no `grid` en mobile: styles.css tiene una clase global `.grid` de dos columnas que le gana a
  // la utilidad de Tailwind.
  return (
    <section
      aria-label="Rendimiento del armado"
      className="mb-[24px] flex flex-col gap-[20px] rounded-card border border-bd bg-s1 p-[16px] md:grid md:grid-cols-2"
    >
      {presentes.map(([slot, titulo, sustantivo]) => (
        <Slot key={slot} slot={slot} titulo={titulo} sustantivo={sustantivo} referencia={referencias[slot]} />
      ))}
    </section>
  );
}
