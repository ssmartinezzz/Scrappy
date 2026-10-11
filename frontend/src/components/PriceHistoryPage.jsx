import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Area, CartesianGrid, ComposedChart, ReferenceArea, ReferenceLine, XAxis, YAxis } from 'recharts';
import { ChartContainer, ChartTooltip } from './ui/chart';
import { ImageWithFallback } from './ui/image-with-fallback';
import { fetchProductoDetalle, fetchTendencias, fmt } from '../api';
import { cn } from '../lib/utils';
import {
  RANGOS, contextoCategoria, dominioPrecio, etiquetaDia, etiquetaTiempo, serieEnRango, ticksTiempo,
} from '../lib/serieHistorial';

/**
 * Vista dedicada del historial de precios de un producto.
 *
 * Se entra por el handle corto (`producto_key`, V25), no por la URL entera ni
 * por un id sustituto. La URL como query param era ilegible; un id habría sido
 * una identidad nueva, y no habría movido una sola forma normal — `productos.url`
 * ya es la clave primaria. El handle es un alias de presentación: 16 hex
 * derivados de la url por una columna generada, con índice único atrás.
 */

function precioCorto(v) {
  if (v >= 1e6) return `$${(v / 1e6).toLocaleString('es-AR', { maximumFractionDigits: 1 })}M`;
  if (v >= 1e3) return `$${(v / 1e3).toLocaleString('es-AR', { maximumFractionDigits: 1 })}k`;
  return `$${v}`;
}

function TooltipPrecio({ active, payload }) {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <div className="bg-s2 border border-border rounded-card p-2 shadow-lg">
      <div className="text-[.7rem] text-t4 mb-1">{etiquetaDia(d.t)}</div>
      <div className="text-sm font-semibold text-t1">ARS ${fmt(d.precio)}</div>
      {!d.real && <div className="text-[.65rem] text-t4">precio vigente, sin cambio ese día</div>}
    </div>
  );
}

function PuntoReal({ cx, cy, index, payload, stroke }) {
  if (!payload.real) return <g key={`p${index}`}/>;
  return <circle key={`p${index}`} cx={cx} cy={cy} r={3} fill={stroke}/>;
}

function SelectorRango({ valor, onChange }) {
  return (
    <fieldset className="inline-flex rounded-btn border border-bd2 bg-s2 p-[2px]">
      <legend className="sr-only">Rango</legend>
      {RANGOS.map(r => (
        <button
          key={r.id}
          type="button"
          onClick={() => onChange(r.id)}
          aria-pressed={valor === r.id}
          className={cn(
            'min-h-[36px] min-w-[40px] cursor-pointer rounded-btn px-2 text-[.75rem] font-semibold',
            '[touch-action:manipulation] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
            valor === r.id ? 'bg-primary text-white' : 'text-t3 hover:text-t1'
          )}
        >
          {r.id}
        </button>
      ))}
    </fieldset>
  );
}

function Stat({ label, children, tone = 'text-t1' }) {
  return (
    <div>
      <div className="text-[.68rem] text-t4">{label}</div>
      <div className={`text-sm font-semibold ${tone}`}>{children}</div>
    </div>
  );
}

export default function PriceHistoryPage() {
  const { key } = useParams();

  const [data, setData]       = useState(null);
  const [loading, setLoading] = useState(true);
  const [rango, setRango]     = useState('Todo');
  const [distribucion, setDistribucion] = useState(null);

  // Dos puntos es el mínimo para que una serie signifique algo.
  const puntosApi = data?.historial?.puntos;
  const serie = useMemo(
    () => (puntosApi?.length >= 2 ? serieEnRango(puntosApi, rango) : null),
    [puntosApi, rango]
  );

  useEffect(() => {
    if (!key) { setLoading(false); return; }
    setLoading(true);
    fetchProductoDetalle(key)
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [key]);

  // Optional context: a failed or empty /api/tendencias just drops the median.
  useEffect(() => {
    fetchTendencias()
      .then(r => setDistribucion(r.data?.distribucionCategorias ?? null))
      .catch(() => setDistribucion(null));
  }, []);

  if (loading) return <div className="p-3 text-t4">Cargando historial…</div>;

  if (!data) {
    return (
      <div className="p-3">
        <p className="text-t3 mb-2">No encontramos ese producto en el catálogo.</p>
        <Link className="text-primary underline" to="/catalogo">← Volver al catálogo</Link>
      </div>
    );
  }

  const p = data.producto || {};

  return (
    <div className="p-3">
      <Link className="text-[.75rem] text-t4 hover:text-t1" to="/catalogo">← Volver al catálogo</Link>

      <header className="flex gap-3 items-start mt-2 mb-3">
        <div className="w-20 shrink-0 aspect-[3/4] overflow-hidden rounded-card">
          <ImageWithFallback
            src={p.img}
            alt={p.nombre}
            className="w-full h-full object-cover"
            fallback={<span className="text-t4 text-[.6rem]">{p.sitio}</span>}
          />
        </div>
        <div className="min-w-0">
          <h1 className="text-base font-semibold text-t1 truncate">{p.nombre}</h1>
          <div className="text-[.72rem] text-t4">{p.sitio}{p.marca ? ` · ${p.marca}` : ''}</div>
          <div className="text-lg font-bold text-t1 mt-1">ARS ${fmt(p.precio)}</div>
          {p.url && (
            <a className="text-[.72rem] text-primary underline" href={p.url}
               target="_blank" rel="noopener noreferrer">Ver en {p.sitio} →</a>
          )}
        </div>
      </header>

      {!serie ? (
        <p className="text-t4 text-[.8rem]">
          📉 Sin historial suficiente todavía — la serie aparece cuando el
          producto se scrapea más de una vez y su precio cambia.
        </p>
      ) : (
        <GraficoHistorial serie={serie} rango={rango} onRango={setRango}
                          precioActual={p.precio} categoria={contextoCategoria(distribucion, p)}/>
      )}
    </div>
  );
}

// Cheaper is good news for a buyer: down is green, up is red.
const TONOS = {
  '-1': { texto: 'text-success', trazo: 'var(--g)' },
  0:    { texto: 'text-t4',      trazo: 'var(--p)' },
  1:    { texto: 'text-danger',  trazo: 'var(--r)' },
};

function GraficoHistorial({ serie, rango, onRango, precioActual, categoria }) {
  const { desde, hasta, stats } = serie;
  const { domain, ticks, incluyeReferencia } = dominioPrecio(stats.min, stats.max, categoria?.mediana);
  const bandaVisible = categoria && categoria.q3 >= domain[0] && categoria.q1 <= domain[1];
  const vsMediana = categoria && precioActual > 0
    ? Math.round((precioActual / categoria.mediana - 1) * 100)
    : null;
  const delta = stats.deltaPct;
  const color = TONOS[Math.sign(delta)].trazo;

  return (
    <>
      <div className="mb-3">
        <SelectorRango valor={rango} onChange={onRango}/>
      </div>

      <div className="flex flex-wrap gap-4 mb-2">
        <Stat label="Mínimo">ARS ${fmt(stats.min)}</Stat>
        <Stat label="Máximo">ARS ${fmt(stats.max)}</Stat>
        <Stat label="Promedio">ARS ${fmt(stats.avg)}</Stat>
        <Stat label="Variación" tone={TONOS[Math.sign(delta)].texto}>
          {delta > 0 ? '+' : ''}{delta}%
        </Stat>
        <Stat label="Registros">{stats.registros}</Stat>
        {vsMediana !== null && (
          <Stat label={`vs. mediana ${categoria.nombre}`}
                tone={TONOS[Math.sign(vsMediana)].texto}>
            {vsMediana > 0 ? '+' : ''}{vsMediana}%
          </Stat>
        )}
      </div>

      {categoria && !incluyeReferencia && (
        <p className="text-[.7rem] text-t4 mb-1">
          Mediana de {categoria.nombre}: ARS ${fmt(categoria.mediana)}
          {categoria.mediana > stats.max ? ' ↑' : ' ↓'} fuera de escala
        </p>
      )}

      <ChartContainer className="h-72 w-full">
        <ComposedChart data={serie.puntos} margin={{ top: 16, right: 16, left: 0, bottom: 8 }}>
          <defs>
            <linearGradient id="phGradient" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor={color} stopOpacity={0.25}/>
              <stop offset="100%" stopColor={color} stopOpacity={0}/>
            </linearGradient>
          </defs>

          <CartesianGrid strokeDasharray="4 8" stroke="var(--bd)" vertical={false}/>

          {bandaVisible && (
            <ReferenceArea y1={categoria.q1} y2={categoria.q3} ifOverflow="hidden"
                           fill="var(--t4)" fillOpacity={0.08} stroke="none"/>
          )}
          {categoria && incluyeReferencia && (
            <ReferenceLine y={categoria.mediana} stroke="var(--t3)" strokeDasharray="4 4"
                           label={{ value: 'mediana', fill: 'var(--t4)', fontSize: 10, position: 'insideTopLeft' }}/>
          )}

          <XAxis dataKey="t" type="number" scale="time" domain={[desde, hasta]}
                 ticks={ticksTiempo(desde, hasta)}
                 tickFormatter={t => etiquetaTiempo(t, desde, hasta)}
                 axisLine={false} tickLine={false}
                 tick={{ fontSize: 11, fill: 'var(--t4)' }} tickMargin={10}/>
          <YAxis domain={domain} ticks={ticks} allowDataOverflow
                 axisLine={false} tickLine={false}
                 tick={{ fontSize: 11, fill: 'var(--t4)' }} tickMargin={8} width={56}
                 tickFormatter={precioCorto}/>

          <ChartTooltip content={<TooltipPrecio/>}
                        cursor={{ strokeDasharray: '3 3', stroke: 'var(--t4)', strokeOpacity: 0.5 }}/>

          {/* stepAfter: el precio rige hasta el próximo cambio registrado. */}
          <Area type="stepAfter" dataKey="precio" stroke={color} strokeWidth={2}
                fill="url(#phGradient)" dot={<PuntoReal/>}
                activeDot={{ r: 5, fill: color, stroke: 'var(--bg)', strokeWidth: 2 }}/>
        </ComposedChart>
      </ChartContainer>

      {bandaVisible && (
        <div className="flex flex-wrap gap-4 mt-1 text-[.68rem] text-t4">
          <span className="inline-flex items-center gap-1">
            <span aria-hidden="true" className="inline-block w-3 h-2 rounded-sm"
                  style={{ background: 'var(--t4)', opacity: 0.25 }}/>
            rango típico de {categoria.nombre} (q1–q3)
          </span>
          {incluyeReferencia && (
            <span className="inline-flex items-center gap-1">
              <span aria-hidden="true" className="inline-block w-3 border-t border-dashed border-t3"/>
              mediana ARS ${fmt(categoria.mediana)}
            </span>
          )}
        </div>
      )}
    </>
  );
}
