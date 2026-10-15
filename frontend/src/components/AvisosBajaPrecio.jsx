import { useCallback, useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  cambiarEmailCuenta, fetchCuenta, fetchPreferenciaAvisos, savePreferenciaAvisos,
} from '../api';

const FILA = { padding: '.5rem 1.25rem', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 };
const ALERTA = { fontSize: '.7rem', color: 'var(--danger, #c0392b)' };

/**
 * The address is the password-reset channel, so every change re-asks the password.
 * `email: null` removes it.
 */
function FormularioDeEmail({ emailActual, onGuardado, onCancelar }) {
  const [email, setEmail] = useState(emailActual || '');
  const [password, setPassword] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [error, setError] = useState('');

  async function enviar(nuevoEmail) {
    setEnviando(true);
    setError('');
    try {
      const r = await cambiarEmailCuenta({ password, email: nuevoEmail });
      if (r?.ok) onGuardado();
      else setError(r?.mensaje || 'No se pudo guardar. Probá de nuevo.');
    } catch {
      setError('No se pudo guardar. Probá de nuevo.');
    } finally {
      setEnviando(false);
    }
  }

  return (
    <form
      style={{ ...FILA, width: '100%' }}
      onSubmit={e => { e.preventDefault(); enviar(email.trim() || null); }}
    >
      <Label htmlFor="cuenta-email" className="text-[.75rem]">Email</Label>
      <Input
        id="cuenta-email" type="email" className="h-8 w-56 text-[.75rem]"
        value={email} onChange={e => setEmail(e.target.value)}
      />
      <Label htmlFor="cuenta-password" className="text-[.75rem]">Contraseña actual</Label>
      <Input
        id="cuenta-password" type="password" autoComplete="current-password" className="h-8 w-44 text-[.75rem]"
        value={password} onChange={e => setPassword(e.target.value)}
      />
      <Button type="submit" size="sm" disabled={enviando || !password || !email.trim()}>Guardar</Button>
      {emailActual && (
        <Button type="button" size="sm" variant="ghost" disabled={enviando || !password}
          onClick={() => enviar(null)}>
          Quitar email
        </Button>
      )}
      <Button type="button" size="sm" variant="ghost" onClick={onCancelar}>Cancelar</Button>
      {error && <span role="alert" style={ALERTA}>{error}</span>}
    </form>
  );
}

/**
 * The opt-out for price-drop mail. Hidden only while the preference is unknown; an account with
 * no email is invited to add one, since that is the one thing standing between it and the alerts.
 */
export default function AvisosBajaPrecio() {
  const [preferencia, setPreferencia] = useState(null);
  const [cuenta, setCuenta] = useState(null);
  const [editando, setEditando] = useState(false);
  const [guardando, setGuardando] = useState(false);
  const [fallo, setFallo] = useState(false);

  const cargar = useCallback(() => {
    let vigente = true;
    Promise.all([fetchPreferenciaAvisos(), fetchCuenta()])
      .then(([p, c]) => { if (vigente) { setPreferencia(p); setCuenta(c ?? null); } })
      .catch(() => {});
    return () => { vigente = false; };
  }, []);

  useEffect(() => cargar(), [cargar]);

  if (!preferencia) return null;

  async function alternar(activar) {
    setGuardando(true);
    setFallo(false);
    try {
      const nueva = await savePreferenciaAvisos(activar);
      if (nueva) setPreferencia(nueva);
      else setFallo(true);
    } catch {
      setFallo(true);
    } finally {
      setGuardando(false);
    }
  }

  function guardado() {
    setEditando(false);
    cargar();
  }

  if (editando) {
    return (
      <FormularioDeEmail
        emailActual={cuenta?.email}
        onGuardado={guardado}
        onCancelar={() => setEditando(false)}
      />
    );
  }

  if (!preferencia.tieneEmail) {
    return (
      <div style={FILA}>
        <span className="text-[.75rem]">Sumá tu email para enterarte cuando un favorito baje de precio.</span>
        <Button size="sm" variant="outline" onClick={() => setEditando(true)}>Agregar email</Button>
      </div>
    );
  }

  return (
    <div style={FILA}>
      <Checkbox
        id="avisos-baja-precio"
        checked={preferencia.notificarBajasPrecio}
        disabled={guardando}
        onCheckedChange={v => alternar(v === true)}
      />
      <Label htmlFor="avisos-baja-precio" className="text-[.75rem]">
        Avisarme por mail cuando un favorito baje más de 5%
      </Label>
      {cuenta?.email && <span className="text-[.7rem] text-t3">{cuenta.email}</span>}
      <Button size="sm" variant="ghost" onClick={() => setEditando(true)}>Cambiar email</Button>
      {fallo && <span role="alert" style={ALERTA}>No se pudo guardar. Probá de nuevo.</span>}
    </div>
  );
}
