import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useDragControls, useMotionValue } from 'framer-motion';
import { MessageSquare, X, Sparkles, Send } from 'lucide-react';
import { cn } from '@/lib/utils';
import { renderRichText } from '@/lib/richText';
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
 *
 * Each assistant message also carries the `trace` of tool calls that produced
 * it (agent-chat-continuity), resent with the history so the backend can
 * rebuild a transcript in which the model can see its previous answers came
 * from tools. A trace holds only tool NAMES and ARGUMENTS — never results,
 * which the server re-executes against the live catalog — so nothing stored
 * here can become a catalog fact the backend did not just produce.
 */
const STORAGE_KEY = 'agentChat:v1';

/** Idle window after which a persisted conversation is dropped on load. */
const STORAGE_TTL_MS = 30 * 60 * 1000;

/** Hard caps so a bloated or tampered snapshot can't flood state and the DOM. */
const MAX_MESSAGES = 100;
const MAX_PROPOSALS = 20;
const MAX_TEXT_LEN = 4000;
const MAX_FIELD_LEN = 500;

/** Caps on a stored tool trace. Each one has a server-side counterpart with
 *  the same value (`AGENT_MAX_TRACE_STEPS`, `AGENT_MAX_TRACE_CALLS_PER_STEP`,
 *  `AGENT_MAX_TRACE_ARG_KEYS`, `AGENT_MAX_TRACE_ARG_LEN` in `ApiController`) —
 *  these are the convenience copy, the server's are the enforced ones. */
const MAX_TRACE_STEPS = 8;
const MAX_TRACE_CALLS = 6;
const MAX_TRACE_ARG_KEYS = 8;
// Its own constant rather than reusing MAX_FIELD_LEN, which bounds unrelated
// proposal/conflict fields — sharing it would silently desync this pair the
// day the other one is retuned.
const MAX_TRACE_ARG_LEN = 500;

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

/** Tool arguments as the three catalog tools actually declare them: a flat
 *  object of scalars (url / query / categoria / limit …). Rebuilt key by key
 *  so a nested, cyclic or oversized value from storage can never ride along
 *  to `POST /api/agent/chat`. */
function sanitizeArgs(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  const clean = {};
  for (const [k, v] of Object.entries(raw).slice(0, MAX_TRACE_ARG_KEYS)) {
    const key = k.slice(0, MAX_TRACE_ARG_LEN);
    if (typeof v === 'string') clean[key] = v.slice(0, MAX_TRACE_ARG_LEN);
    else if (typeof v === 'number' || typeof v === 'boolean') clean[key] = v;
  }
  return clean;
}

/** An assistant turn's tool activity (agent-chat-continuity): the calls it
 *  issued, grouped by step. Carries no results — the server re-executes every
 *  call against the live catalog, so what's stored here can never become a
 *  catalog "fact" the backend didn't just produce. A step left with no usable
 *  call is dropped entirely rather than sent empty. */
function sanitizeTrace(raw) {
  if (!Array.isArray(raw)) return [];
  return raw
    .slice(0, MAX_TRACE_STEPS)
    .map(step => ({
      calls: (Array.isArray(step?.calls) ? step.calls : [])
        .slice(0, MAX_TRACE_CALLS)
        .map(c => ({ name: asString(c?.name, MAX_FIELD_LEN), arguments: sanitizeArgs(c?.arguments) }))
        .filter(c => c.name),
    }))
    .filter(step => step.calls.length > 0);
}

function sanitizeMessage(m) {
  if (!m || typeof m !== 'object') return null;
  if (m.role !== 'user' && m.role !== 'assistant') return null;
  const text = asString(m.text, MAX_TEXT_LEN);
  if (!text) return null;
  // Only an assistant turn can carry tool activity; the server enforces the
  // same rule on the way in, so a traced user turn is dropped on both sides.
  const trace = m.role === 'assistant' ? sanitizeTrace(m.trace) : [];
  return trace.length > 0 ? { role: m.role, text, trace } : { role: m.role, text };
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
  // Anchor (index of the assistant message this proposal accompanies) and
  // client-side identity, mirroring the _applied/_mensaje typed-optional
  // pattern above — absent on a pre-change snapshot, which is exactly the
  // "render in the trailing block, fall back to index key" degradation.
  if (typeof p._turn === 'number') clean._turn = p._turn;
  if (typeof p._cid === 'number') clean._cid = p._cid;
  return clean;
}

function sanitizeSnapshot(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const rawMessages = Array.isArray(raw.messages) ? raw.messages : [];
  const messages = rawMessages.slice(-MAX_MESSAGES).map(sanitizeMessage).filter(Boolean);
  // Front-truncation invalidates any surviving _turn index (it could now
  // point at the wrong message) — degrade those proposals to the trailing
  // block instead of trying to recompute a shifted index.
  const truncated = rawMessages.length > MAX_MESSAGES;
  const proposals = (Array.isArray(raw.proposals) ? raw.proposals : [])
    .slice(-MAX_PROPOSALS)
    .map(sanitizeProposal)
    .filter(Boolean)
    .map(p => {
      if (!truncated || p._turn === undefined) return p;
      const { _turn, ...rest } = p;
      return rest;
    });
  return {
    open: raw.open === true,
    model: asString(raw.model, MAX_FIELD_LEN),
    messages,
    proposals,
  };
}

// ─── Pure helpers (no React dependency — directly unit-testable the day a
// frontend test harness lands, per design's A8 mitigation) ─────────────────

/** Next client-side id for a new proposal, derived from the accumulator
 * itself — since `prev` always includes every previously-seen proposal for
 * this session (restored ones included, accumulate-never-replace), this
 * cannot collide with a restored id without any extra seeding mechanism. */
function nextCid(proposals) {
  let max = 0;
  for (const p of proposals) {
    if (typeof p._cid === 'number' && p._cid > max) max = p._cid;
  }
  return max + 1;
}

/** Accumulates `incoming` proposals onto `prev` (never replaces), tagging
 * each with a fresh `_cid` and, when `turnIndex` is a number, a `_turn`
 * anchor. Caps to MAX_PROPOSALS so a long session can't flood the DOM. */
function appendProposals(prev, incoming, turnIndex) {
  if (!incoming || incoming.length === 0) return prev;
  let cid = nextCid(prev);
  const tagged = incoming.map(p => {
    const withCid = { ...p, _cid: cid++ };
    return typeof turnIndex === 'number' ? { ...withCid, _turn: turnIndex } : withCid;
  });
  return [...prev, ...tagged].slice(-MAX_PROPOSALS);
}

/** Groups proposals by the message index they're anchored to; anything
 * without a valid `_turn` (legacy/restored/exhausted-turn proposals) renders
 * as a trailing block, in conversation order, after the last message. */
function anchorGroups(messages, proposals) {
  const byTurn = new Map();
  const trailing = [];
  proposals.forEach((proposal, idx) => {
    const entry = { proposal, idx };
    if (typeof proposal._turn === 'number' && proposal._turn >= 0 && proposal._turn < messages.length) {
      if (!byTurn.has(proposal._turn)) byTurn.set(proposal._turn, []);
      byTurn.get(proposal._turn).push(entry);
    } else {
      trailing.push(entry);
    }
  });
  return { byTurn, trailing };
}

/** Stable render key for a proposal: `_cid` when present (every
 * newly-accumulated proposal has one), else the index fallback for a
 * pre-change/legacy restored proposal. */
const proposalRenderKey = ({ proposal, idx }) => (typeof proposal._cid === 'number' ? proposal._cid : `legacy-${idx}`);

/** Identity used by the confirm/reject/requery handlers — `_cid` when
 * present, else the object reference itself (legacy proposals). */
const proposalIdentity = (p) => (typeof p._cid === 'number' ? p._cid : p);

/** The confirmation that enters the transcript once the server reports a
 *  reclassification was really written — a statement of fact about a completed
 *  write, deliberately terse and structured so it doesn't read as free prose. */
const appliedNotice = (proposal) =>
  `Cambio aplicado en el catálogo: «${proposal.nombreProducto}» → categoría ${proposal.categoriaPropuesta}.`;

const OPTIONAL_FIELD_LABELS = {
  subCategoriaPropuesta: 'Subcategoría',
  marcaPropuesta: 'Marca',
  generoPropuesto: 'Género',
};

/** Which rows a ProposalCard must render for a given proposal: the category
 * diff only when it actually changes, one "se guardará" row per non-blank
 * optional field disclosing the value that will be WRITTEN (never claimed as
 * a proposed change — the payload carries no baseline for these fields, so a
 * diff can't be proven; `optionalText` on the server falls back to the
 * product's current value whenever the model didn't touch the field).
 *
 * There is deliberately no "is this a no-op?" flag. `categoria` is the only
 * field with a baseline, and it is a REQUIRED tool argument, so a proposal
 * that only changes marca/genero/subCategoria must still resend the current
 * category — indistinguishable from a true no-op on the client. Gating the
 * confirm button on that guess would block a legitimate reclassification;
 * confirming a genuine no-op merely writes the same values back. */
function disclosureRows(proposal) {
  const categoryChanged = proposal.categoriaPropuesta !== proposal.categoriaActual;
  const optionalRows = Object.entries(OPTIONAL_FIELD_LABELS)
    .filter(([field]) => typeof proposal[field] === 'string' && proposal[field].trim() !== '')
    .map(([field, label]) => ({ field, label, value: proposal[field] }));
  return { categoryChanged, optionalRows };
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
  /** Ephemeral, turn-scoped notice (ungrounded rejection, loop exhaustion, or
   * a provider failure) — NEVER persisted and NEVER pushed into `messages`,
   * so it can't poison the history resent to the provider on the next turn. */
  const [notice, setNotice] = useState(null);
  const scrollRef = useRef(null);

  // ── Draggable dock ────────────────────────────────────────────────────────
  // The widget floats anywhere on screen: `boundsRef` is a full-viewport,
  // click-through surface used as the drag constraint, and only the FAB starts
  // a drag (dragListener={false} + dragControls) so text selection and
  // scrolling inside the open panel keep working.
  const boundsRef = useRef(null);
  const dockRef = useRef(null);
  const dragControls = useDragControls();
  const draggingRef = useRef(false);
  const x = useMotionValue(0);
  const y = useMotionValue(0);
  const [flipped, setFlipped] = useState(false);

  /** Keeps the dock fully on screen — the drag constraint only applies while
   * dragging, so a resize, a rotation or the panel opening can still strand it. */
  const clampIntoView = () => {
    const el = dockRef.current;
    if (!el || typeof el.getBoundingClientRect !== 'function') return;
    const r = el.getBoundingClientRect();
    if (!r.width && !r.height) return;
    const m = 8;
    let dx = 0;
    let dy = 0;
    if (r.right > window.innerWidth - m) dx = window.innerWidth - m - r.right;
    if (r.left + dx < m) dx += m - (r.left + dx);
    if (r.bottom > window.innerHeight - m) dy = window.innerHeight - m - r.bottom;
    if (r.top + dy < m) dy += m - (r.top + dy);
    if (dx) x.set(x.get() + dx);
    if (dy) y.set(y.get() + dy);
  };

  /** Opening upwards from the top half would push the panel off screen, so the
   * stack flips to render below the FAB once it is dropped up there. */
  const endDrag = () => {
    const r = dockRef.current?.getBoundingClientRect?.();
    if (r) setFlipped(r.top + r.height / 2 < window.innerHeight / 2);
    // Cleared after the click event this pointer-up also fires, so a drag
    // never toggles the panel; a plain click never reaches this handler.
    setTimeout(() => { draggingRef.current = false; }, 0);
  };

  const toggleOpen = () => {
    if (draggingRef.current) return;
    setOpen(o => !o);
  };

  useEffect(() => {
    window.addEventListener('resize', clampIntoView);
    return () => window.removeEventListener('resize', clampIntoView);
  }, []);

  useLayoutEffect(() => { clampIntoView(); }, [open]);

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
  }, [messages, proposals, notice, sending, open]);

  /**
   * Runs one turn against the already-committed `historyForApi` (the user
   * message this turn answers is expected to already be its last element).
   * Shared by `send()` (which pushes a fresh user message first) and
   * `retry()` (which re-sends the SAME already-visible user message instead
   * of pushing a duplicate) — this is what a failed turn's Retry button
   * calls, so N retries never grow the transcript or the resent history by
   * more than the one original user turn.
   */
  const runTurn = async (historyForApi, turnIndex) => {
    setSending(true);
    setScrapeWait(false);
    setNotice(null);

    let resp;
    try {
      resp = await askAgent(historyForApi, model || undefined);
    } catch {
      // `askAgent` awaits fetch without a try/catch of its own, so a
      // network-level rejection (backend killed mid-turn) surfaces here rather
      // than as its `{error:true}` shape. Same channel as any other failed
      // turn, so Retry stays available.
      setNotice({ kind: 'failure', text: 'No se pudo consultar al agente.' });
      return;
    } finally {
      // Load-bearing: `sending` gates the input AND — since proposal cards are
      // disabled while a turn is in flight — every pending confirmation. Left
      // stuck true by a throw, the whole widget freezes until a page reload.
      setSending(false);
    }

    if (resp?.scraping) { setScrapeWait(true); return; }
    if (resp?.error) {
      // Grounding is mandatory: a provider failure is never delivered through
      // the chat-bubble channel. The user's message stays visible above
      // (already in `historyForApi`); this notice is separate and offers
      // Retry, which re-sends the same history without duplicating it.
      setNotice({ kind: 'failure', text: resp.mensaje });
      return;
    }

    // Only a grounded/deterministic answer (complete/capability) becomes a
    // durable message that gets resent as history next turn. An ungrounded
    // rejection or loop exhaustion is an ephemeral notice instead — it never
    // enters `messages`, so it can't poison the next turn's payload.
    if (resp.outcome === 'ungrounded') {
      setNotice({ kind: 'ungrounded', text: resp.assistantText });
      return;
    }
    if (resp.outcome === 'exhausted') {
      setNotice({ kind: 'exhausted', text: resp.assistantText });
      setProposals(ps => appendProposals(ps, resp.proposals, null));
      return;
    }
    // The turn's tool trace is stored WITH the message it belongs to and
    // resent next turn, so the server can rebuild a transcript where the model
    // sees its own answers came from tools instead of appearing out of thin
    // air — the cause of it silently giving up on tool calls mid-conversation.
    // Kept off the message entirely when empty, so a turn that used no tools
    // stores exactly the shape it stored before this change.
    const trace = sanitizeTrace(resp.trace);
    setMessages(m => [...m, trace.length > 0
      ? { role: 'assistant', text: resp.assistantText, trace }
      : { role: 'assistant', text: resp.assistantText }]);
    setProposals(ps => appendProposals(ps, resp.proposals, turnIndex));
  };

  const send = async (preset) => {
    const text = (preset ?? input).trim();
    if (!text || sending) return;
    const nextMessages = [...messages, { role: 'user', text }];
    setMessages(nextMessages);
    setInput('');
    // The assistant reply, if this turn produces a durable one, lands at
    // this index — computed up front since we already know the exact
    // length of the history this turn is answering.
    const turnIndex = nextMessages.length;
    await runTurn(nextMessages, turnIndex);
  };

  /** "Reintentar" on a provider-failure notice: the failed user message is
   * already the last item in `messages` (never rolled back, per spec — it
   * must stay visible throughout) — re-send that SAME history instead of
   * pushing another copy of it, so retries never duplicate the user turn. */
  const retry = () => {
    if (sending) return;
    runTurn(messages, messages.length);
  };

  const confirmProposal = async (proposal) => {
    // Never mutate the transcript while a turn is in flight: `runTurn` computed
    // its proposal anchor from the message count before awaiting, and appending
    // the confirmation below would shift it out from under that turn.
    if (sending) return;
    const res = await applyProposal(proposal);
    if (res?.scraping) { setScrapeWait(true); return; }
    // 422 conflicto_stale (WU5): the product changed since this proposal was
    // generated — surface the live values instead of a plain "no se pudo
    // aplicar", and never let the card offer a resend of stale data.
    if (res?.codigo === 'conflicto_stale') {
      setProposals(ps => ps.map(p => proposalIdentity(p) === proposalIdentity(proposal)
        ? { ...p, _conflicto: res.actual || {} }
        : p));
      return;
    }
    setProposals(ps => ps.map(p => proposalIdentity(p) === proposalIdentity(proposal)
      ? { ...p, _applied: !!res?.ok, _mensaje: res?.mensaje }
      : p));
    // A confirmed write is the one event in this widget the model never used to
    // learn about: it saw its own proposal and then nothing, so the next turn
    // still reasoned over the pre-change classification. Recording it as a turn
    // (assistant role — the only authorable role that speaks in the thread, and
    // claiming the USER said it would be a lie) both closes that gap and gives
    // the user a visible confirmation in the conversation, not just on the card.
    // Appended only after the server reported the write actually happened.
    // Tradeoff taken knowingly: this turn carries no trace, so it replays as
    // bare prose — the shape this change exists to stop producing. Accepted
    // because it states a completed WRITE rather than answering a catalog
    // question, so it doesn't model "answer without consulting the catalog";
    // fabricating a trace for a call the model never made would be worse.
    if (res?.ok) {
      setMessages(m => [...m, { role: 'assistant', text: appliedNotice(proposal) }]);
    }
  };

  const rejectProposal = (proposal) => {
    setProposals(ps => ps.filter(p => proposalIdentity(p) !== proposalIdentity(proposal)));
  };

  /** "Volver a consultar" (T6.2): drop the stale card and re-ask the agent for
   * a fresh proposal on the same product, instead of ever resending the old one. */
  const requeryProposal = (proposal) => {
    setProposals(ps => ps.filter(p => proposalIdentity(p) !== proposalIdentity(proposal)));
    send(`Volvé a revisar la clasificación de ${proposal.url} — la propuesta anterior quedó desactualizada.`);
  };

  const anchoredProposals = anchorGroups(messages, proposals);

  /** Single render call site for a proposal card — shared by the anchored
   * (`byTurn`) and trailing render loops below, so a prop change is made
   * once instead of twice. */
  const renderProposalCard = (entry) => (
    <ProposalCard
      key={proposalRenderKey(entry)}
      proposal={entry.proposal}
      onConfirm={confirmProposal}
      onReject={rejectProposal}
      onRequery={requeryProposal}
      busy={sending}
    />
  );

  return (
    <div ref={boundsRef} className="pointer-events-none fixed inset-0 z-[60]">
      <motion.div
        ref={dockRef}
        drag
        dragListener={false}
        dragControls={dragControls}
        dragConstraints={boundsRef}
        dragElastic={0}
        dragMomentum={false}
        onDragStart={() => { draggingRef.current = true; }}
        onDragEnd={endDrag}
        style={{ x, y, bottom: 'calc(88px + var(--compare-bar-h, 0px))' }}
        className={cn(
          'pointer-events-auto absolute right-4 flex w-max items-end gap-3 sm:right-6',
          flipped ? 'flex-col-reverse' : 'flex-col',
        )}
      >
      <AnimatePresence>
        {open && (
          <motion.div
            key="agent-panel"
            variants={panelVariants}
            initial="hidden"
            animate="visible"
            exit="exit"
            className="flex max-h-[70vh] w-[min(380px,calc(100vw-2rem))] flex-col overflow-hidden rounded-card border border-border bg-s2 shadow-2xl"
          >
            {/* Header */}
            <div className="flex items-center justify-between gap-2 border-b border-border bg-s1 px-4 py-3">
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
                <div key={i} className="flex flex-col gap-2">
                  <div
                    className={cn(
                      'flex max-w-[85%] flex-col rounded-2xl px-3 py-2 text-[.75rem] leading-snug',
                      m.role === 'user'
                        ? 'self-end rounded-tr-sm bg-primary text-white'
                        : 'self-start rounded-tl-sm bg-s3 text-t2 [&_a]:text-primary',
                    )}
                  >
                    {/* Only model output gets markdown treatment — what the user
                        typed is shown verbatim. */}
                    {m.role === 'assistant' ? renderRichText(m.text) : m.text}
                  </div>
                  {/* Proposal cards anchored to this message render right here,
                      in conversation order — not detached at the bottom. */}
                  {(anchoredProposals.byTurn.get(i) || []).map(renderProposalCard)}
                </div>
              ))}
              {/* Legacy/restored/exhausted-turn proposals with no valid anchor
                  render as a trailing block, same as before this change. */}
              {anchoredProposals.trailing.map(renderProposalCard)}
              {sending && <div className="self-start text-[.7rem] italic text-t4">Pensando…</div>}
              {scrapeWait && (
                <div className="text-[.72rem] italic text-warning">
                  Hay un scraping en curso — esperá a que termine para usar el agente.
                </div>
              )}
              {/* Ungrounded rejection / loop exhaustion / provider-failure
                  notice — visually distinct from proposal cards so a pending
                  card is never mistaken for a response to a failed turn. */}
              {notice && (
                <div
                  className={cn(
                    'rounded-card border px-3 py-2 text-[.72rem] italic leading-snug',
                    notice.kind === 'failure'
                      ? 'border-danger/40 bg-danger/10 text-danger'
                      : 'border-bd2 bg-s3 text-t3',
                  )}
                >
                  <span>{notice.text}</span>
                  {notice.kind === 'failure' && (
                    <button
                      onClick={retry}
                      disabled={sending}
                      className="ml-2 rounded-btn border border-danger px-2 py-0.5 font-semibold not-italic text-danger transition-colors hover:bg-danger hover:text-white disabled:opacity-40"
                    >
                      Reintentar
                    </button>
                  )}
                </div>
              )}
            </div>

            {/* Input */}
            <div className="flex items-center gap-2 border-t border-border bg-s1 px-3 py-2.5">
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

      {/* FAB — also the drag handle for the whole widget */}
      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onPointerDown={e => dragControls.start(e)}
        onClick={toggleOpen}
        title="Ask Agent"
        style={{ touchAction: 'none' }}
        className={cn(
          'flex h-14 w-14 cursor-grab items-center justify-center rounded-full text-white shadow-2xl transition-colors active:cursor-grabbing',
          open ? 'bg-danger' : 'bg-primary hover:bg-primary2',
        )}
      >
        {open ? <X className="h-6 w-6" /> : <MessageSquare className="h-6 w-6" />}
      </motion.button>
      </motion.div>
    </div>
  );
}

function ProposalCard({ proposal, onConfirm, onReject, onRequery, busy }) {
  const applied = proposal._applied;
  const conflicto = proposal._conflicto;
  const { categoryChanged, optionalRows } = disclosureRows(proposal);
  return (
    <div className="rounded-card border border-primary/25 bg-primary/[.08] px-3 py-2.5 text-[.72rem]">
      <div className="mb-1 font-bold text-t1">{proposal.nombreProducto}</div>
      {/* Category row — suppressed when the proposal doesn't actually change
          it, since an arrow between two identical values is a misleading diff. */}
      {categoryChanged && (
        <div className="text-t3">
          <span className="text-t4">{proposal.categoriaActual}</span>
          {' → '}
          <strong className="text-primary">{proposal.categoriaPropuesta}</strong>
        </div>
      )}
      {/* One row per non-blank optional field, disclosing the value that will
          be SAVED — not asserted as a proposed change, since the payload
          carries no baseline for these fields and most already hold the
          product's current, unchanged value (server fallback). */}
      {optionalRows.map(row => (
        <div key={row.field} className="mt-0.5 text-t3">
          <span className="text-t4">Se guardará / {row.label}:</span>{' '}
          <strong className="text-primary">{row.value}</strong>
        </div>
      ))}
      {!categoryChanged && !conflicto && (
        <div className="mt-1.5 text-t4">La categoría no cambia.</div>
      )}
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
              disabled={busy}
              className="rounded-btn border border-primary px-3 py-1 font-semibold text-primary transition-colors hover:bg-primary hover:text-white disabled:opacity-40"
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
              {/* [Sí] is always offered: the client cannot prove a proposal
                  changes nothing (see disclosureRows), and hiding it would
                  make a marca/genero/subCategoria-only fix unappliable. */}
              <button
                onClick={() => onConfirm(proposal)}
                disabled={busy}
                className="rounded-btn border border-primary px-3 py-1 font-semibold text-primary transition-colors hover:bg-primary hover:text-white disabled:opacity-40"
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
