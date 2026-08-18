import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CartesianGrid, ComposedChart, Line, ReferenceLine, XAxis, YAxis } from 'recharts';
import { ChartContainer, ChartTooltip } from './ui/chart';
import { ImageWithFallback } from './ui/image-with-fallback';
import { fetchProductoDetalle, fmt } from '../api';

/**
 * Vista dedicada del historial de precios de un producto.
 *
 * Se entra por el handle corto (`producto_key`, V25), no por la URL entera ni
 * por un id sustituto. La URL como query param era ilegible; un id habría sido
 * una identidad nueva, y no habría movido una sola forma normal — `productos.url`
 * ya es la clave primaria. El handle es un alias de presentación: 16 hex
 * derivados de la url por una columna generada, con índice único atrás.
 */

// Una fecha ISO a etiqueta corta, sin dependencias: '2026-05-28' -> '28 may'.
const MESES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
function etiquetaFecha(iso) {
  const [, mes, dia] = String(iso ?? '').split('-');
  if (!mes || !dia) return String(iso ?? '');
  return `${Number(dia)} ${MESES[Number(mes) - 1] ?? ''}`.trim();
}

function TooltipPrecio({ active, payload }) {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <div className="bg-s2 border border-border rounded-card p-2 shadow-lg">
      <div className="text-[.7rem] text-t4 mb-1">{d.fecha}</div>
      <div className="text-sm font-semibold text-t1">ARS ${fmt(d.precio)}</div>
    </div>
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

  useEffect(() => {
    if (!key) { setLoading(false); return; }
    setLoading(true);
    fetchProductoDetalle(key)
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [key]);

  if (loading) return <div className="p-3 text-t4">Cargando historial…</div>;

  if (!data) {
    return (
      <div className="p-3">
        <p className="text-t3 mb-2">No encontramos ese producto en el catálogo.</p>
        <Link className="text-primary underline" to="/catalogo">← Volver al catálogo</Link>
      </div>
    );
  }

  const p    = data.producto || {};
  const hist = data.historial || {};
  const pts  = hist.puntos || [];
  // Dos puntos es el mínimo para que una serie signifique algo. Con uno, el
  // backend ya se abstiene de mandar min/max/avg (ver HistorialJson).
  const haySerie = pts.length >= 2;
  const delta    = hist.deltaPct;
  const tonoDelta = delta === undefined ? 'text-t4' : delta < 0 ? 'text-success' : 'text-danger';

  const serie = pts.map(pt => ({ ...pt, etiqueta: etiquetaFecha(pt.fecha) }));

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

      {!haySerie ? (
        <p className="text-t4 text-[.8rem]">
          📉 Sin historial suficiente todavía — la serie aparece cuando el
          producto se scrapea más de una vez y su precio cambia.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-4 mb-2">
            <Stat label="Mínimo">ARS ${fmt(hist.min)}</Stat>
            <Stat label="Máximo">ARS ${fmt(hist.max)}</Stat>
            <Stat label="Promedio">ARS ${fmt(hist.avg)}</Stat>
            <Stat label="Variación" tone={tonoDelta}>
              {delta > 0 ? '+' : ''}{delta}%
            </Stat>
            <Stat label="Registros">{pts.length}</Stat>
          </div>

          <ChartContainer className="h-72 w-full">
            <ComposedChart data={serie} margin={{ top: 16, right: 16, left: 8, bottom: 8 }}>
              <defs>
                <linearGradient id="phGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%"   stopColor="var(--p)" stopOpacity={0.25}/>
                  <stop offset="100%" stopColor="var(--p)" stopOpacity={0}/>
                </linearGradient>
              </defs>

              <CartesianGrid strokeDasharray="4 8" stroke="var(--bd)" vertical={false}/>

              <ReferenceLine y={hist.min} stroke="var(--g)" strokeDasharray="4 4"
                             label={{ value: 'mín', fill: 'var(--t4)', fontSize: 10, position: 'insideBottomLeft' }}/>
              <ReferenceLine y={hist.max} stroke="var(--r)" strokeDasharray="4 4"
                             label={{ value: 'máx', fill: 'var(--t4)', fontSize: 10, position: 'insideTopLeft' }}/>

              <XAxis dataKey="etiqueta" axisLine={false} tickLine={false}
                     tick={{ fontSize: 11, fill: 'var(--t4)' }} tickMargin={10}
                     interval="preserveStartEnd"/>
              <YAxis axisLine={false} tickLine={false}
                     tick={{ fontSize: 11, fill: 'var(--t4)' }} tickMargin={8} width={64}
                     domain={['dataMin', 'dataMax']}
                     tickFormatter={v => `$${fmt(v)}`}/>

              <ChartTooltip content={<TooltipPrecio/>}
                            cursor={{ strokeDasharray: '3 3', stroke: 'var(--t4)', strokeOpacity: 0.5 }}/>

              <Line type="monotone" dataKey="precio" stroke="var(--p)" strokeWidth={2}
                    fill="url(#phGradient)"
                    dot={{ r: 3, fill: 'var(--p)' }}
                    activeDot={{ r: 5, fill: 'var(--p)', stroke: 'var(--bg)', strokeWidth: 2 }}/>
            </ComposedChart>
          </ChartContainer>
        </>
      )}
    </div>
  );
}
