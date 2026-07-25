import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { MessageSquare, X, Sparkles, Send } from 'lucide-react';
import { cn } from '@/lib/utils';
import { fetchAgentModels, askAgent, applyProposal } from '../api';

/**
 * Floating chat widget for the LLM catalog agent (llm-catalog-nlp).
 * Self-contained: owns its own FAB + open state, and is mounted once at the
 * layout level (not inside a route), so the conversation survives navigation.
 * Real wiring — `GET /api/agent/models` populates the model selector (D8),
 * `POST /api/agent/chat` drives the conversation, and each reclassification
 * proposal renders a {@link ProposalCard} committed via `POST /api/agent/apply`
 * ([Sí]) or discarded locally ([No]). A 409 (scrape in progress) shows a clear
 * "wait" message instead of a generic error.
 *
 * Conversation state is mirrored to sessionStorage so a page reload keeps it
 * too; persistence is best-effort and never blocks the UI if storage fails.
 * The snapshot carries a sliding TTL — `savedAt` is refreshed on every write,
 * so a thread expires after {@link STORAGE_TTL_MS} of inactivity and the widget
 * comes back on the starter prompts instead of a stale conversation.
 *
 * What comes back out of storage is NOT trusted: {@link sanitizeSnapshot}
 * rebuilds it field by field against the expected shape. Restored proposals in
 * particular are replayed to `POST /api/agent/apply`, so they are reduced to
 * the server's `ReclassifyProposal` fields and nothing else.
 */
const STORAGE_KEY = 'agentChat:v1';

/** Idle window after which a persisted conversation is dropped on load. */
const STORAGE_TTL_MS = 30 * 60 * 1000;

/** Hard caps so a bloated or tampered snapshot can't flood state and the DOM. */
const MAX_MESSAGES = 100;
const MAX_PROPOSALS = 20;
const MAX_TEXT_LEN = 4000;
const MAX_FIELD_LEN = 500;

/** Exactly the fields of the server-side `ReclassifyProposal` record. */
const PROPOSAL_FIELDS = [
  'url',
  'nombreProducto',
  'categoriaActual',
  'categoriaPropuesta',
  'subCategoriaPropuesta',
  'marcaPropuesta',
  'generoPropuesto',
];

const asString = (v, max) => (typeof v === 'string' ? v.slice(0, max) : '');

const isHttpUrl = (v) => {
  try {
    return ['http:', 'https:'].includes(new URL(v).protocol);
  } catch {
    return false;
  }
};

function sanitizeMessage(m) {
  if (!m || typeof m !== 'object') return null;
  if (m.role !== 'user' && m.role !== 'assistant') return null;
  const text = asString(m.text, MAX_TEXT_LEN);
  return text ? { role: m.role, text } : null;
}

/** Known-safe keys of the 422 conflicto_stale `actual` payload — see conflictoStale() server-side. */
const CONFLICTO_FIELDS = ['categoria', 'marca', 'genero', 'subCategoria'];

function sanitizeProposal(p) {
  if (!p || typeof p !== 'object') return null;
  // Rebuilt key by key (never spread): anything the server never sent is dropped
  // instead of riding along to POST /api/agent/apply.
  const clean = {};
  for (const f of PROPOSAL_FIELDS) clean[f] = asString(p[f], MAX_FIELD_LEN);
  if (!isHttpUrl(clean.url) || !clean.categoriaPropuesta) return null;
  // Keep the applied/rejected/conflict UI state, but only in its expected
  // types — `_applied`/`_conflicto` stay undefined while the proposal is
  // still pending.
  if (typeof p._applied === 'boolean') clean._applied = p._applied;
  if (p._mensaje !== undefined) clean._mensaje = asString(p._mensaje, MAX_FIELD_LEN);
  if (p._conflicto && typeof p._conflicto === 'object') {
    const conflicto = {};
    for (const f of CONFLICTO_FIELDS) conflicto[f] = asString(p._conflicto[f], MAX_FIELD_LEN);
    clean._conflicto = conflicto;
  }
  return clean;
}

function sanitizeSnapshot(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const messages = (Array.isArray(raw.messages) ? raw.messages : [])
    .slice(-MAX_MESSAGES)
    .map(sanitizeMessage)
    .filter(Boolean);
  const proposals = (Array.isArray(raw.proposals) ? raw.proposals : [])
    .slice(-MAX_PROPOSALS)
    .map(sanitizeProposal)
    .filter(Boolean);
  return {
    open: raw.open === true,
    model: asString(raw.model, MAX_FIELD_LEN),
    messages,
    proposals,
  };
}

function loadPersisted() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    if (!parsed || typeof parsed !== 'object') return null;
    // Negated form so a missing/corrupt savedAt (NaN) also counts as expired.
    if (!(Date.now() - parsed.savedAt < STORAGE_TTL_MS)) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return sanitizeSnapshot(parsed);
  } catch {
    return null;
  }
}

/** Starter prompts shown on the empty state to guide the first question. */
const SUGGESTED_PROMPTS = [
  'Buscá remeras oversize en el catálogo',
  '¿Qué productos hay de la marca Nike?',
  'Mostrame un buzo y cómo está clasificado',
  'Revisá la categoría de un pantalón cargo',
];

const panelVariants = {
  hidden:  { opacity: 0, y: 20, scale: 0.95 },
  visible: { opacity: 1, y: 0, scale: 1, transition: { type: 'spring', damping: 26, stiffness: 320 } },
  exit:    { opacity: 0, y: 20, scale: 0.95, transition: { duration: 0.15 } },
};

export default function AgentChatPanel() {
  const [restored] = useState(loadPersisted);
  const [open, setOpen] = useState(!!restored?.open);
  const [models, setModels] = useState({ available: [], default: '' });
  const [model, setModel] = useState(restored?.model || '');
  const [messages, setMessages] = useState(restored?.messages || []);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [scrapeWait, setScrapeWait] = useState(false);
  const [proposals, setProposals] = useState(restored?.proposals || []);
  const scrollRef = useRef(null);

  useEffect(() => {
    fetchAgentModels().then(m => {
      if (!m) return;
      setModels(m);
      // Keep a restored selection only if the server still offers it (a model
      // can be removed from Ollama, or the value can be tampered with).
      setModel(prev => (m.available?.includes(prev) ? prev : (m.default || '')));
    });
  }, []);

  useEffect(() => {
    try {
      sessionStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ savedAt: Date.now(), open, model, messages, proposals }),
      );
    } catch {
      // Storage full or unavailable (private mode) — persistence is optional.
    }
  }, [open, model, messages, proposals]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, proposals, sending, open]);

  const send = async (preset) => {
    const text = (preset ?? input).trim();
    if (!text || sending) return;
    setSending(true);
    setScrapeWait(false);
    const nextMessages = [...messages, { role: 'user', text }];
    setMessages(nextMessages);
    setInput('');

    const resp = await askAgent(nextMessages, model || undefined);
    setSending(false);

    if (resp?.scraping) { setScrapeWait(true); return; }
    if (resp?.error) {
      setMessages(m => [...m, { role: 'assistant', text: resp.mensaje }]);
      return;
    }
    setMessages(m => [...m, { role: 'assistant', text: resp.assistantText }]);
    setProposals(resp.proposals || []);
  };

  const confirmProposal = async (proposal) => {
    const res = await applyProposal(proposal);
    if (res?.scraping) { setScrapeWait(true); return; }
    // 422 conflicto_stale (WU5): the product changed since this proposal was
    // generated — surface the live values instead of a plain "no se pudo
    // aplicar", and never let the card offer a resend of stale data.
    if (res?.codigo === 'conflicto_stale') {
      setProposals(ps => ps.map(p => p === proposal
        ? { ...p, _conflicto: res.actual || {} }
        : p));
      return;
    }
    setProposals(ps => ps.map(p => p === proposal
      ? { ...p, _applied: !!res?.ok, _mensaje: res?.mensaje }
      : p));
  };

  const rejectProposal = (proposal) => {
    setProposals(ps => ps.filter(p => p !== proposal));
  };

  /** "Volver a consultar" (T6.2): drop the stale card and re-ask the agent for
   * a fresh proposal on the same product, instead of ever resending the old one. */
  const requeryProposal = (proposal) => {
    setProposals(ps => ps.filter(p => p !== proposal));
    send(`Volvé a revisar la clasificación de ${proposal.url} — la propuesta anterior quedó desactualizada.`);
  };

  return (
    <div
      className="fixed right-6 z-[60] flex flex-col items-end gap-3"
      style={{ bottom: 'calc(88px + var(--compare-bar-h, 0px))' }}
    >
      <AnimatePresence>
        {open && (
          <motion.div
            key="agent-panel"
            variants={panelVariants}
            initial="hidden"
            animate="visible"
            exit="exit"
            className="flex max-h-[70vh] w-[380px] flex-col overflow-hidden rounded-card border border-border bg-s2/95 shadow-2xl backdrop-blur-xl"
          >
            {/* Header */}
            <div className="flex items-center justify-between gap-2 border-b border-border bg-s1/60 px-4 py-3">
              <div className="flex items-center gap-2">
                <div className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/15 text-primary">
                  <Sparkles className="h-4 w-4" />
                </div>
                <div>
                  <div className="text-[.8rem] font-bold leading-tight text-t1">Agente del catálogo</div>
                  <div className="text-[.62rem] text-t4">Propone · vos confirmás</div>
                </div>
              </div>
              <button
                onClick={() => setOpen(false)}
                className="rounded-full p-1 text-t3 transition-colors hover:bg-s3 hover:text-t1"
                aria-label="Cerrar"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Model selector (D8) */}
            <div className="border-b border-border px-4 py-2">
              <label className="mb-1 block text-[.58rem] font-semibold uppercase tracking-wide text-t4">Modelo</label>
              <select
                value={model}
                onChange={e => setModel(e.target.value)}
                className="w-full rounded-btn border border-bd2 bg-s1 px-2 py-1.5 text-[.72rem] text-t2 outline-none transition-colors focus:border-primary"
              >
                {models.available.length === 0 && <option value="">(sin modelos disponibles)</option>}
                {models.available.map(m => (
                  <option key={m} value={m}>{m}{m === models.default ? ' (default)' : ''}</option>
                ))}
              </select>
            </div>

            {/* Messages */}
            <div ref={scrollRef} className="flex flex-1 flex-col gap-2 overflow-y-auto px-4 py-3">
              {messages.length === 0 && !scrapeWait && (
                <div className="flex flex-col gap-2.5">
                  <div className="text-[.72rem] leading-relaxed text-t2">
                    Preguntame sobre un producto del catálogo — puedo buscarlo, verlo y proponerte una
                    re-clasificación. Nada se guarda hasta que confirmes.
                  </div>
                  <div className="text-[.58rem] font-semibold uppercase tracking-wide text-t3">
                    Para empezar
                  </div>
                  <div className="flex flex-col items-start gap-1.5">
                    {SUGGESTED_PROMPTS.map(p => (
                      <button
                        key={p}
                        onClick={() => send(p)}
                        disabled={sending}
                        className="rounded-full border border-bd2 bg-s1 px-3 py-1.5 text-left text-[.7rem] text-t2 transition-colors hover:border-primary hover:text-primary disabled:opacity-40"
                      >
                        {p}
                      </button>
                    ))}
                  </div>
                </div>
              )}
              {messages.map((m, i) => (
                <div
                  key={i}
                  className={cn(
                    'max-w-[85%] rounded-2xl px-3 py-2 text-[.75rem] leading-snug',
                    m.role === 'user'
                      ? 'self-end rounded-tr-sm bg-primary text-white'
                      : 'self-start rounded-tl-sm bg-s3 text-t2',
                  )}
                >
                  {m.text}
                </div>
              ))}
              {proposals.map((p, i) => (
                <ProposalCard
                  key={i}
                  proposal={p}
                  onConfirm={confirmProposal}
                  onReject={rejectProposal}
                  onRequery={requeryProposal}
                />
              ))}
              {sending && <div className="self-start text-[.7rem] italic text-t4">Pensando…</div>}
              {scrapeWait && (
                <div className="text-[.72rem] italic text-warning">
                  Hay un scraping en curso — esperá a que termine para usar el agente.
                </div>
              )}
            </div>

            {/* Input */}
            <div className="flex items-center gap-2 border-t border-border bg-s1/60 px-3 py-2.5">
              <input
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') send(); }}
                placeholder="Preguntale al agente…"
                className="flex-1 rounded-full border border-bd2 bg-s1 px-3 py-2 text-[.75rem] text-t1 outline-none transition-colors focus:border-primary"
              />
              <button
                onClick={() => send()}
                disabled={sending || !input.trim()}
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary text-white transition hover:bg-primary2 disabled:opacity-40"
                aria-label="Enviar"
              >
                <Send className="h-4 w-4" />
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* FAB */}
      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onClick={() => setOpen(o => !o)}
        title="Ask Agent"
        className={cn(
          'flex h-14 w-14 items-center justify-center rounded-full text-white shadow-2xl transition-colors',
          open ? 'bg-danger' : 'bg-primary hover:bg-primary2',
        )}
      >
        {open ? <X className="h-6 w-6" /> : <MessageSquare className="h-6 w-6" />}
      </motion.button>
    </div>
  );
}

function ProposalCard({ proposal, onConfirm, onReject, onRequery }) {
  const applied = proposal._applied;
  const conflicto = proposal._conflicto;
  return (
    <div className="rounded-card border border-primary/25 bg-primary/[.08] px-3 py-2.5 text-[.72rem]">
      <div className="mb-1 font-bold text-t1">{proposal.nombreProducto}</div>
      <div className="text-t3">
        <span className="text-t4">{proposal.categoriaActual}</span>
        {' → '}
        <strong className="text-primary">{proposal.categoriaPropuesta}</strong>
      </div>
      {/* 422 conflicto_stale (WU5): el producto cambió desde que se generó la
          propuesta — mostramos el valor real actual y NUNCA ofrecemos [Sí]
          (reenviar el payload viejo), solo volver a consultar al agente. */}
      {conflicto ? (
        <>
          <div className="mt-1.5 text-warning">
            El producto cambió desde que se generó esta propuesta.
          </div>
          <div className="mt-1 text-t3">
            Categoría actual: <strong className="text-t2">{conflicto.categoria || '—'}</strong>
          </div>
          <div className="mt-2 flex gap-2">
            <button
              onClick={() => onRequery(proposal)}
              className="rounded-btn border border-primary px-3 py-1 font-semibold text-primary transition-colors hover:bg-primary hover:text-white"
            >
              Volver a consultar
            </button>
            <button
              onClick={() => onReject(proposal)}
              className="rounded-btn border border-bd2 px-3 py-1 font-semibold text-t3 transition-colors hover:border-t3"
            >
              Descartar
            </button>
          </div>
        </>
      ) : (
        <>
          {applied === undefined && (
            <div className="mt-2 flex gap-2">
              <button
                onClick={() => onConfirm(proposal)}
                className="rounded-btn border border-primary px-3 py-1 font-semibold text-primary transition-colors hover:bg-primary hover:text-white"
              >
                Sí
              </button>
              <button
                onClick={() => onReject(proposal)}
                className="rounded-btn border border-bd2 px-3 py-1 font-semibold text-t3 transition-colors hover:border-t3"
              >
                No
              </button>
            </div>
          )}
          {applied === true && <div className="mt-1.5 text-success">Aplicado ✓</div>}
          {applied === false && <div className="mt-1.5 text-t4">No se pudo aplicar: {proposal._mensaje}</div>}
        </>
      )}
    </div>
  );
}
