import { useEffect, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Cpu, PackageSearch, Unplug } from 'lucide-react';
import { fetchIndices, fetchPcPreferencia, fetchPcsBuilder, fmt, savePcPreferencia } from '../api';
import { MoneyInput } from './ui/money-input';
import { cn } from '@/lib/utils';

const SLOT_LABELS = {
  mother: 'Motherboard',
  cpu: 'CPU',
  cooler: 'Cooler',
  ram: 'RAM',
  gabinete: 'Gabinete',
  fuente: 'Fuente',
  gpu: 'Placa de video',
  almacenamiento: 'Almacenamiento',
  // pc-builder-homelab (D4/D6): reemplazan a `almacenamiento` cuando uso=homelab.
  sistema: 'Disco de sistema',
  datos: 'Disco de datos',
  minipc: 'Mini PC',
};

function slotLabel(slot) {
  return SLOT_LABELS[slot] ?? slot;
}

// '' es "sin filtro": el servidor arma sin gama, como antes de la fase 6. Los
// otros tres son el vocabulario de cable de GamaWire, no el del enum Java.
const GAMAS = [
  { value: '', label: 'Cualquiera' },
  { value: 'economica', label: 'Económica' },
  { value: 'media', label: 'Media' },
  { value: 'alta', label: 'Alta' },
];

// pc-builder-homelab (D3). A diferencia de GAMAS, '' NO es "sin filtro": es
// GAMING, el default real de siempre (Uso no tiene estado de abstención —
// ver UsoWire). Mismo truco que gama para el cable: '' se omite del
// querystring y de la preferencia guardada, así que el armado gamer de hoy
// sigue byte a byte igual sin tocar nada.
const USOS = [
  { value: '', label: 'Gaming' },
  { value: 'homelab', label: 'Homelab' },
];

// Las cuatro listas de abajo son el vocabulario de cable de PreferenciasWire
// (D8) — no los enums Java. '' es siempre "no pedida".
const DDRS = [
  { value: '', label: 'Cualquiera' },
  { value: 'ddr4', label: 'DDR4' },
  { value: 'ddr5', label: 'DDR5' },
];
const MARCAS_CPU = [
  { value: '', label: 'Cualquiera' },
  { value: 'intel', label: 'Intel' },
  { value: 'amd', label: 'AMD' },
];
const MARCAS_GPU = [
  { value: '', label: 'Cualquiera' },
  { value: 'nvidia', label: 'NVIDIA' },
  { value: 'amd', label: 'AMD' },
];
const TIPOS_ALMACENAMIENTO = [
  { value: '', label: 'Cualquiera' },
  { value: 'nvme', label: 'M.2 NVMe' },
  { value: 'sata', label: 'SSD SATA' },
  { value: 'hdd', label: 'HDD' },
];

// Fase 9. Las dos primeras son vocabulario de cable de PreferenciasWire; las
// dos últimas son PISOS en su propia unidad, y 0 es "no pedido" (el servidor
// rechaza un 0 explícito a propósito).
const TAMANIOS_GABINETE = [
  { value: '', label: 'Cualquiera' },
  { value: 'mini', label: 'Mini tower' },
  { value: 'mid', label: 'Mid tower' },
  { value: 'full', label: 'Full tower' },
];
const TIPOS_COOLER = [
  { value: '', label: 'Cualquiera' },
  { value: 'liquido', label: 'Líquida' },
  { value: 'aire', label: 'Aire' },
];
// 1 TB = 1024 GB: el parser normaliza TB a GB ×1024, así que el piso viaja
// en GB y el chip sólo traduce la etiqueta.
const CAPACIDADES = [
  { value: 0, label: 'Cualquiera' },
  { value: 240, label: '240 GB' },
  { value: 500, label: '500 GB' },
  { value: 1024, label: '1 TB' },
  { value: 2048, label: '2 TB' },
];
const WATTS = [
  { value: 0, label: 'Cualquiera' },
  { value: 550, label: '550 W' },
  { value: 650, label: '650 W' },
  { value: 750, label: '750 W' },
  { value: 850, label: '850 W' },
  { value: 1000, label: '1000 W' },
];

const TIER_CHIPSET_LABEL = { 1: 'X/Z', 2: 'B', 3: 'A/H' };
const TAMANIO_GABINETE_LABEL = { MINI: 'mini tower', MID: 'mid tower', FULL: 'full tower' };

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
  if (specs.marcaChip) partes.push(specs.marcaChip);
  // Una sola grafía por marca (no por slot): Intel es "gen N", todo lo demás
  // (AMD, y cualquier GPU) es "serie N000".
  if (specs.generacion) {
    partes.push(specs.marcaChip === 'INTEL' ? `gen ${specs.generacion}` : `serie ${specs.generacion}000`);
  }
  if (specs.tierChipset) partes.push(TIER_CHIPSET_LABEL[specs.tierChipset]);
  if (specs.modulos) partes.push(`${specs.modulos}x`);
  if (specs.wifi) partes.push('WiFi');
  if (specs.tipoCooler === 'LIQUIDO') partes.push('AIO');
  if (specs.tipoCooler === 'AIRE') partes.push('aire');
  if (specs.radiadorMm) partes.push(`${specs.radiadorMm} mm`);
  if (TAMANIO_GABINETE_LABEL[specs.tamanioGabinete]) {
    partes.push(TAMANIO_GABINETE_LABEL[specs.tamanioGabinete]);
  }
  return partes.join(' · ');
}

function ChipGroup({ label, options, value, onChange }) {
  return (
    <div>
      <p className="mb-[6px] text-[.8rem] font-semibold text-t3">{label}</p>
      <div className="flex flex-wrap items-center gap-[8px]" role="group" aria-label={label}>
        {options.map(o => (
          <button
            key={o.value}
            type="button"
            onClick={() => onChange(o.value)}
            aria-pressed={value === o.value}
            className={cn(
              'inline-flex min-h-[44px] cursor-pointer items-center rounded-btn px-[16px] py-[8px] text-[.9rem]',
              '[touch-action:manipulation] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
              value === o.value
                ? 'border border-transparent bg-primary text-white'
                : 'border border-bd2 bg-s2 text-t2 hover:border-primary'
            )}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function ToggleChip({ label, pressed, onToggle }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={pressed}
      className={cn(
        'inline-flex min-h-[44px] cursor-pointer items-center rounded-btn px-[16px] py-[8px] text-[.9rem]',
        '[touch-action:manipulation] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
        pressed
          ? 'border border-transparent bg-primary text-white'
          : 'border border-bd2 bg-s2 text-t2 hover:border-primary'
      )}
    >
      {label}
    </button>
  );
}

export default function PcsPanel({ onSavePc } = {}) {
  const [presupuesto, setPresupuesto] = useState('');
  const [conGpu, setConGpu] = useState(false);
  const [gama, setGama] = useState('');
  // '' = Gaming (default). Ver USOS.
  const [uso, setUso] = useState('');
  const [ddr, setDdr] = useState('');
  const [marcaCpu, setMarcaCpu] = useState('');
  const [marcaGpu, setMarcaGpu] = useState('');
  const [tipoAlmacenamiento, setTipoAlmacenamiento] = useState('');
  const [ramDual, setRamDual] = useState(false);
  const [wifi, setWifi] = useState(false);
  const [tamanioGabinete, setTamanioGabinete] = useState('');
  const [tipoCooler, setTipoCooler] = useState('');
  const [capacidadMinimaGb, setCapacidadMinimaGb] = useState(0);
  const [wattsMinimos, setWattsMinimos] = useState(0);
  // Cotización del MISMO servicio que el badge del header (GET /api/indices,
  // dólar oficial de indices-service). null = no hay dato: no se muestra la
  // línea en dólares en vez de inventar una tasa — ver CLAUDE.md, "Un factor
  // nunca viaja sin marcar".
  const [cotizacionUsd, setCotizacionUsd] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [data, setData] = useState(null);
  const [saving, setSaving] = useState(false);
  // URLs ya mostradas, agrupadas por SLOT. El pick del servidor es determinístico
  // a propósito, así que "Regenerar" tiene que decir qué vio para recibir el
  // siguiente — mismo contrato que SuplementosPanel, pero por slot en vez de tipo.
  const [vistos, setVistos] = useState({});
  const reduceMotion = useReducedMotion();

  // La preferencia guardada sólo precarga los controles: el armado sigue
  // siendo un click explícito, y manda gama= por su cuenta.
  useEffect(() => {
    let vivo = true;
    fetchPcPreferencia()
      .then(pref => {
        if (!vivo || !pref) return;
        setGama(pref.gama ?? '');
        // El backend siempre manda un uso concreto ("gaming"/"homelab") desde
        // V39; una preferencia vieja (guardada antes de esa migración) puede
        // no traer el campo — cualquier otra cosa que no sea "homelab" cae
        // al default, igual que UsoWire.parse.
        setUso(pref.uso === 'homelab' ? 'homelab' : '');
        setPresupuesto(pref.presupuesto != null ? String(pref.presupuesto) : '');
        setConGpu(Boolean(pref.conGpu));
        setDdr(pref.ddr ?? '');
        setMarcaCpu(pref.marcaCpu ?? '');
        setMarcaGpu(pref.marcaGpu ?? '');
        setTipoAlmacenamiento(pref.tipoAlmacenamiento ?? '');
        setRamDual(Boolean(pref.ramDual));
        setWifi(Boolean(pref.wifi));
        setTamanioGabinete(pref.tamanioGabinete ?? '');
        setTipoCooler(pref.tipoCooler ?? '');
        setCapacidadMinimaGb(pref.capacidadMinimaGb ?? 0);
        setWattsMinimos(pref.wattsMinimos ?? 0);
      })
      .catch(() => {});
    return () => { vivo = false; };
  }, []);

  useEffect(() => {
    let vivo = true;
    fetchIndices()
      .then(d => {
        if (!vivo) return;
        const usd = d?.usd;
        if (!usd || usd.confianza === 'sin_datos' || !usd.ultimoValor) return;
        setCotizacionUsd(usd.ultimoValor);
      })
      .catch(() => {});
    return () => { vivo = false; };
  }, []);

  /**
   * @param {boolean} acumulando  true desde "Regenerar": arrastra lo ya visto para
   *   pedir el siguiente candidato. false desde "Generar": consulta nueva, se
   *   empieza de cero para que el primer resultado sea siempre el mejor del catálogo.
   */
  async function generar(acumulando = false) {
    setLoading(true);
    setError(null);
    // El PUT exige gama, así que "Cualquiera" no persiste nada. Es best-effort:
    // que falle guardar la preferencia no puede frenar el armado.
    if (!acumulando && gama) {
      savePcPreferencia({
        gama,
        presupuesto: presupuesto ? Number(presupuesto) : null,
        conGpu,
        ddr: ddr || null,
        marcaCpu: marcaCpu || null,
        marcaGpu: marcaGpu || null,
        tipoAlmacenamiento: tipoAlmacenamiento || null,
        ramDual,
        wifi,
        tamanioGabinete: tamanioGabinete || null,
        tipoCooler: tipoCooler || null,
        capacidadMinimaGb: capacidadMinimaGb || null,
        wattsMinimos: wattsMinimos || null,
        // Sólo viaja cuando difiere del default: UsoWire.parse ya trata
        // ausente/null como GAMING, así que omitir la clave en Gaming deja
        // el payload de siempre byte a byte igual (D3).
        ...(uso === 'homelab' ? { uso } : {}),
      }).catch(() => {});
    }
    const previos = acumulando ? vistos : {};
    const excluir = Object.values(previos).flat();
    try {
      const resp = await fetchPcsBuilder({
        presupuesto: presupuesto ? Number(presupuesto) : 0,
        conGpu,
        excluir,
        gama,
        ddr,
        marcaCpu,
        marcaGpu,
        tipoAlmacenamiento,
        ramDual,
        wifi,
        tamanioGabinete,
        tipoCooler,
        uso,
        capacidadMinimaGb,
        wattsMinimos,
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

  async function guardar() {
    if (!data || !onSavePc || saving) return;
    setSaving(true);
    try {
      await onSavePc({
        nombre: `PC $${fmt(data.totalEstimado)}`,
        picks: data.picks,
        presupuesto: presupuesto ? Number(presupuesto) : 0,
        conGpu,
        gama: gama || null,
        totalEstimado: data.totalEstimado,
      });
    } finally {
      setSaving(false);
    }
  }

  const picks = data?.picks ?? [];
  const sinStock = data?.sinStock ?? [];
  const sinCompatible = data?.sinCompatible ?? [];
  const mensajes = data?.mensajes ?? {};

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-[920px] px-[20px] py-[24px]">
        <p className="mb-[6px] text-eyebrow uppercase text-t3">Armador</p>
        <h1 className="mb-[24px] text-display-2 text-t1">Armador de PCs</h1>

        <div className="mb-[16px]">
          <ChipGroup label="Uso" options={USOS} value={uso} onChange={setUso} />
        </div>

        <div className="mb-[16px] flex flex-wrap items-center gap-[8px]" role="group" aria-label="Gama">
          {GAMAS.map(g => (
            <button
              key={g.value}
              type="button"
              onClick={() => setGama(g.value)}
              aria-pressed={gama === g.value}
              className={cn(
                'inline-flex min-h-[44px] cursor-pointer items-center rounded-btn px-[16px] py-[8px] text-[.9rem]',
                '[touch-action:manipulation] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
                gama === g.value
                  ? 'border border-transparent bg-primary text-white'
                  : 'border border-bd2 bg-s2 text-t2 hover:border-primary'
              )}
            >
              {g.label}
            </button>
          ))}
        </div>

        <div className="mb-[24px] flex flex-col gap-[14px]">
          <ChipGroup label="Memoria" options={DDRS} value={ddr} onChange={setDdr} />
          <ChipGroup label="CPU" options={MARCAS_CPU} value={marcaCpu} onChange={setMarcaCpu} />
          {conGpu && (
            <ChipGroup label="Placa de video" options={MARCAS_GPU} value={marcaGpu} onChange={setMarcaGpu} />
          )}
          <ChipGroup
            label="Disco"
            options={TIPOS_ALMACENAMIENTO}
            value={tipoAlmacenamiento}
            onChange={setTipoAlmacenamiento}
          />
          <ChipGroup
            label="Capacidad mínima del disco"
            options={CAPACIDADES}
            value={capacidadMinimaGb}
            onChange={setCapacidadMinimaGb}
          />
          <ChipGroup label="Refrigeración" options={TIPOS_COOLER} value={tipoCooler} onChange={setTipoCooler} />
          <ChipGroup
            label="Watts mínimos de la fuente"
            options={WATTS}
            value={wattsMinimos}
            onChange={setWattsMinimos}
          />
          <div>
            <ChipGroup
              label="Gabinete"
              options={TAMANIOS_GABINETE}
              value={tamanioGabinete}
              onChange={setTamanioGabinete}
            />
            {/* El dato medido va en pantalla porque el resultado es
                contraintuitivo: sobre las filas ACTIVAS, 535 de 575 gabinetes
                no dicen su tamaño y pedirlo los descarta a todos (D2). Hoy
                "full tower" no tiene ni un candidato activo, así que la frase
                tiene que admitir que el slot puede quedar vacío — decir "muy
                pocas opciones" cuando el número real es cero sería mentir en
                el lugar donde se toma la decisión. */}
            <p className="mt-[6px] text-[.72rem] text-t4">
              Ojo: pocos gabinetes declaran su tamaño en el nombre. Pedirlo deja muy pocas opciones, y
              puede dejar el slot vacío.
            </p>
            {/* D6: en homelab, pedir "Mini tower" no arma una torre chica —
                colapsa todo el armado en un único pick de Mini PC + disco de
                datos. Es contraintuitivo (cambia qué slots aparecen), así que
                se avisa en el mismo lugar donde se elige. */}
            {uso === 'homelab' && (
              <p className="mt-[4px] text-[.72rem] text-t4">
                En Homelab, "Mini" arma un Mini PC + disco de datos, en vez de una torre.
              </p>
            )}
          </div>
          <div className="flex flex-wrap gap-[8px]">
            <ToggleChip label="RAM dual (2x)" pressed={ramDual} onToggle={() => setRamDual(v => !v)} />
            <ToggleChip label="Mother con WiFi" pressed={wifi} onToggle={() => setWifi(v => !v)} />
          </div>
        </div>

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
              onChange={e => {
                setConGpu(e.target.checked);
                if (!e.target.checked) setMarcaGpu('');
              }}
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
              <div className="mb-[16px] flex flex-wrap items-baseline justify-end gap-[8px]">
                <span className="text-[.8rem] font-semibold text-t3">Total estimado</span>
                <span className="text-[1.3rem] font-extrabold tabular-nums text-primary" aria-live="polite">
                  ${fmt(data.totalEstimado)}
                </span>
                {cotizacionUsd && (
                  <span className="text-[.85rem] font-semibold tabular-nums text-t3">
                    {`US$ ${Math.round(data.totalEstimado / cotizacionUsd).toLocaleString('es-AR')}`}
                  </span>
                )}
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
                  {mensajes[slot] && (
                    <span className="text-center text-[.7rem] text-t4">
                      {mensajes[slot]}
                    </span>
                  )}
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
                    {mensajes[slot] || 'ninguna opción compatible con la mother elegida'}
                  </span>
                </div>
              ))}
            </div>

            <div className="flex flex-wrap items-center justify-center gap-[12px]">
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

              {picks.length > 0 && onSavePc && (
                <button
                  onClick={guardar}
                  disabled={saving}
                  className={cn(
                    'inline-flex min-h-[44px] items-center rounded-btn px-[26px]',
                    'text-[.88rem] font-bold transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
                    saving
                      ? 'cursor-not-allowed bg-s3 text-t3'
                      : 'cursor-pointer bg-primary text-white hover:bg-primary2'
                  )}
                >
                  {saving ? 'Guardando...' : '⭐ Guardar PC'}
                </button>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
