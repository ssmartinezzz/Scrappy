import { useEffect, useState } from 'react';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { fetchPreferenciaAvisos, savePreferenciaAvisos } from '../api';

/**
 * The opt-out for price-drop mail. Hidden while unknown and for accounts with no
 * email: a toggle that cannot do anything would only invite the question why.
 */
export default function AvisosBajaPrecio() {
  const [preferencia, setPreferencia] = useState(null);
  const [guardando, setGuardando] = useState(false);
  const [fallo, setFallo] = useState(false);

  useEffect(() => {
    let vigente = true;
    fetchPreferenciaAvisos()
      .then(p => { if (vigente) setPreferencia(p); })
      .catch(() => {});
    return () => { vigente = false; };
  }, []);

  if (!preferencia?.tieneEmail) return null;

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

  return (
    <div style={{ padding: '.5rem 1.25rem', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
      <Checkbox
        id="avisos-baja-precio"
        checked={preferencia.notificarBajasPrecio}
        disabled={guardando}
        onCheckedChange={v => alternar(v === true)}
      />
      <Label htmlFor="avisos-baja-precio" className="text-[.75rem]">
        Avisarme por mail cuando un favorito baje más de 5%
      </Label>
      {fallo && (
        <span role="alert" style={{ fontSize: '.7rem', color: 'var(--danger, #c0392b)' }}>
          No se pudo guardar. Probá de nuevo.
        </span>
      )}
    </div>
  );
}
