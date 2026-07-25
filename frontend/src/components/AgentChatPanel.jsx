import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { MessageSquare, X, Sparkles, Send } from 'lucide-react';
import { cn } from '@/lib/utils';
import { fetchAgentModels, askAgent, applyProposal } from '../api';

/**
 * Floating chat widget for the LLM catalog agent (llm-catalog-nlp).
 * Self-contained: owns its own FAB + open state, so it can be mounted once in
 * the catalog (like the "Construir índice visual" FAB). Real wiring —
 * `GET /api/agent/models` populates the model selector (D8),
 * `POST /api/agent/chat` drives the conversation, and each reclassification
 * proposal renders a {@link ProposalCard} committed via `POST /api/agent/apply`
 * ([Sí]) or discarded locally ([No]). A 409 (scrape in progress) shows a clear
 * "wait" message instead of a generic error.
 */
const panelVariants = {
  hidden:  { opacity: 0, y: 20, scale: 0.95 },
  visible: { opacity: 1, y: 0, scale: 1, transition: { type: 'spring', damping: 26, stiffness: 320 } },
  exit:    { opacity: 0, y: 20, scale: 0.95, transition: { duration: 0.15 } },
};

export default function AgentChatPanel() {
  const [open, setOpen] = useState(false);
  const [models, setModels] = useState({ available: [], default: '' });
  const [model, setModel] = useState('');
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [scrapeWait, setScrapeWait] = useState(false);
  const [proposals, setProposals] = useState([]);
  const scrollRef = useRef(null);

  useEffect(() => {
    fetchAgentModels().then(m => {
      if (!m) return;
      setModels(m);
      setModel(m.default || '');
    });
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, proposals, sending, open]);

  const send = async () => {
    const text = input.trim();
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
    setProposals(ps => ps.map(p => p === proposal
      ? { ...p, _applied: !!res?.ok, _mensaje: res?.mensaje }
      : p));
  };

  const rejectProposal = (proposal) => {
    setProposals(ps => ps.filter(p => p !== proposal));
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
                <div className="text-[.72rem] leading-relaxed text-t4">
                  Preguntame sobre un producto del catálogo — puedo buscarlo, verlo y proponerte una
                  re-clasificación. Nada se guarda hasta que confirmes.
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
                <ProposalCard key={i} proposal={p} onConfirm={confirmProposal} onReject={rejectProposal} />
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
                onClick={send}
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

function ProposalCard({ proposal, onConfirm, onReject }) {
  const applied = proposal._applied;
  return (
    <div className="rounded-card border border-primary/25 bg-primary/[.08] px-3 py-2.5 text-[.72rem]">
      <div className="mb-1 font-bold text-t1">{proposal.nombreProducto}</div>
      <div className="text-t3">
        <span className="text-t4">{proposal.categoriaActual}</span>
        {' → '}
        <strong className="text-primary">{proposal.categoriaPropuesta}</strong>
      </div>
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
    </div>
  );
}
