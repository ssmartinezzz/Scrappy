import { useEffect, useState } from 'react';
import { fetchAgentModels, askAgent, applyProposal } from '../api';

/**
 * Chat panel for the LLM catalog agent (llm-catalog-nlp, design D7).
 * Renders the conversation, a model `<select>` (D8) populated from
 * `GET /api/agent/models`, and a {@link ProposalCard} per reclassification
 * proposal returned by the last agent turn — [Sí] commits via
 * `POST /api/agent/apply`, [No] discards locally with no network call.
 * A 409 (scrape in progress) shows a clear "wait" message instead of a
 * generic error.
 */
export default function AgentChatPanel({ onClose }) {
  const [models, setModels] = useState({ available: [], default: '' });
  const [model, setModel] = useState('');
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [scrapeWait, setScrapeWait] = useState(false);
  const [proposals, setProposals] = useState([]);

  useEffect(() => {
    fetchAgentModels().then(m => {
      if (!m) return;
      setModels(m);
      setModel(m.default || '');
    });
  }, []);

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

    if (resp?.scraping) {
      setScrapeWait(true);
      return;
    }
    if (resp?.error) {
      setMessages(m => [...m, { role: 'assistant', text: resp.mensaje }]);
      return;
    }
    setMessages(m => [...m, { role: 'assistant', text: resp.assistantText }]);
    setProposals(resp.proposals || []);
  };

  const confirmProposal = async (proposal) => {
    const res = await applyProposal(proposal);
    if (res?.scraping) {
      setScrapeWait(true);
      return;
    }
    setProposals(ps => ps.map(p => p === proposal
      ? { ...p, _applied: !!res?.ok, _mensaje: res?.mensaje }
      : p));
  };

  const rejectProposal = (proposal) => {
    setProposals(ps => ps.filter(p => p !== proposal));
  };

  return (
    <div style={{
      position: 'fixed', bottom: 16, right: 16, width: 380, maxHeight: '70vh',
      background: 'var(--s2)', border: '1px solid var(--bd)', borderRadius: 12,
      display: 'flex', flexDirection: 'column', zIndex: 1000,
      boxShadow: '0 8px 30px rgba(0,0,0,.35)',
    }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '.7rem .9rem', borderBottom: '1px solid var(--bd)',
      }}>
        <span style={{ fontSize: '.82rem', fontWeight: 800, color: 'var(--t1)' }}>Agente del catálogo</span>
        <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--t3)', cursor: 'pointer' }}>✕</button>
      </div>

      <div style={{ padding: '.5rem .9rem', borderBottom: '1px solid var(--bd)' }}>
        <select
          value={model}
          onChange={e => setModel(e.target.value)}
          style={{ width: '100%', fontSize: '.72rem', padding: '4px 6px', borderRadius: 6 }}
        >
          {models.available.length === 0 && <option value="">(sin modelos disponibles)</option>}
          {models.available.map(m => (
            <option key={m} value={m}>{m}{m === models.default ? ' (default)' : ''}</option>
          ))}
        </select>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '.7rem .9rem', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {messages.map((m, i) => (
          <div key={i} style={{
            alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
            background: m.role === 'user' ? 'var(--p)' : 'var(--s3)',
            color: m.role === 'user' ? '#fff' : 'var(--t2)',
            borderRadius: 10, padding: '.45rem .65rem', fontSize: '.75rem', maxWidth: '85%',
          }}>
            {m.text}
          </div>
        ))}

        {proposals.map((p, i) => (
          <ProposalCard key={i} proposal={p} onConfirm={confirmProposal} onReject={rejectProposal} />
        ))}

        {scrapeWait && (
          <div style={{ fontSize: '.72rem', color: 'var(--t3)', fontStyle: 'italic' }}>
            Hay un scraping en curso — esperá a que termine para usar el agente.
          </div>
        )}
      </div>

      <div style={{ display: 'flex', gap: 6, padding: '.7rem .9rem', borderTop: '1px solid var(--bd)' }}>
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') send(); }}
          placeholder="Preguntale al agente sobre el catálogo..."
          style={{ flex: 1, fontSize: '.75rem', padding: '6px 8px', borderRadius: 8 }}
        />
        <button onClick={send} disabled={sending || !input.trim()} style={{
          padding: '6px 12px', borderRadius: 8, border: '1px solid var(--p)',
          background: 'var(--p)', color: '#fff', fontSize: '.72rem', fontWeight: 600,
          cursor: sending ? 'not-allowed' : 'pointer', opacity: sending ? .6 : 1,
        }}>
          {sending ? '...' : 'Enviar'}
        </button>
      </div>
    </div>
  );
}

function ProposalCard({ proposal, onConfirm, onReject }) {
  const applied = proposal._applied;
  return (
    <div style={{
      background: 'color-mix(in srgb, var(--p) 8%, transparent)',
      border: '1px solid color-mix(in srgb, var(--p) 25%, transparent)',
      borderRadius: 10, padding: '.55rem .7rem', fontSize: '.72rem',
    }}>
      <div style={{ fontWeight: 700, marginBottom: 4 }}>{proposal.nombreProducto}</div>
      <div>
        <span style={{ color: 'var(--t4)' }}>{proposal.categoriaActual}</span>
        {' → '}
        <strong style={{ color: 'var(--p)' }}>{proposal.categoriaPropuesta}</strong>
      </div>
      {applied === undefined && (
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button onClick={() => onConfirm(proposal)} style={btnStyle('var(--p)')}>Sí</button>
          <button onClick={() => onReject(proposal)} style={btnStyle('var(--t3)')}>No</button>
        </div>
      )}
      {applied === true && (
        <div style={{ marginTop: 6, color: 'var(--t2)' }}>Aplicado ✓</div>
      )}
      {applied === false && (
        <div style={{ marginTop: 6, color: 'var(--t4)' }}>No se pudo aplicar: {proposal._mensaje}</div>
      )}
    </div>
  );
}

function btnStyle(color) {
  return {
    padding: '3px 10px', borderRadius: 6, border: `1px solid ${color}`,
    background: 'transparent', color, fontSize: '.7rem', fontWeight: 600, cursor: 'pointer',
  };
}
