// Panel plegable de /admin/manage/users: estado del SMTP (sólo lectura) y mail de prueba.
// La configuración vive en el entorno del backend; acá no se edita ni se ve ningún secreto.
import { useEffect, useState } from 'react';
import { ChevronDown, ChevronUp, Mail } from 'lucide-react';

import { enviarCorreoDePrueba, fetchEstadoSmtp } from '../api';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';

function Dato({ etiqueta, children }) {
  return (
    <div className="flex items-baseline justify-between gap-2 border-b border-border py-1 last:border-0">
      <dt className="text-t4 text-xs">{etiqueta}</dt>
      <dd className="min-w-0 break-all text-right text-sm text-t1">{children}</dd>
    </div>
  );
}

function Estado({ estado }) {
  if (estado === undefined) return <p className="text-sm text-t4">Leyendo configuración…</p>;
  if (estado === null) return <p className="text-danger text-sm">No pude leer la configuración del correo.</p>;
  return (
    <>
      <div className="mb-1">
        <Badge variant={estado.configurado ? 'success' : 'warning'}>
          {estado.configurado ? 'Configurado' : 'Sin configurar'}
        </Badge>
      </div>
      {estado.configurado && (
        <dl>
          <Dato etiqueta="Servidor">{estado.host}</Dato>
          <Dato etiqueta="Puerto">{estado.puerto}</Dato>
          <Dato etiqueta="Remitente">{estado.remitente || '—'}</Dato>
          <Dato etiqueta="STARTTLS">{estado.starttls ? 'Obligatorio' : 'Opcional'}</Dato>
          <Dato etiqueta="Usuario">{estado.usuarioConfigurado ? 'Cargado' : 'Sin cargar'}</Dato>
        </dl>
      )}
      {estado.problema && <p className="mt-1 text-danger text-sm">{estado.problema}</p>}
    </>
  );
}

export default function CorreoSalientePanel() {
  const [abierto, setAbierto]   = useState(false);
  const [estado, setEstado]     = useState(undefined);   // undefined = sin leer, null = falló
  const [destino, setDestino]   = useState('');
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState(null);      // { ok, mensaje, pista }

  // Se lee al abrir por primera vez: la pantalla de cuentas no depende del SMTP.
  useEffect(() => {
    if (!abierto || estado !== undefined) return;
    fetchEstadoSmtp().then(setEstado, () => setEstado(null));
  }, [abierto, estado]);

  async function enviar(e) {
    e.preventDefault();
    setEnviando(true);
    setResultado(null);
    try {
      const { ok, body } = await enviarCorreoDePrueba(destino.trim() || undefined);
      setResultado({ ok, mensaje: body?.mensaje || (ok ? 'Mail de prueba enviado.' : 'La operación falló.'), pista: body?.pista });
    } catch {
      setResultado({ ok: false, mensaje: 'No pude contactar al backend.' });
    } finally {
      setEnviando(false);
    }
  }

  return (
    <section className="mb-3">
      <Button
        aria-controls="correo-saliente"
        aria-expanded={abierto}
        className="h-11 w-full justify-between sm:h-9 sm:w-auto sm:justify-center"
        onClick={() => setAbierto(v => !v)}
        variant="ghost"
      >
        <span className="flex items-center">
          <Mail aria-hidden="true" className="mr-1 h-[16px] w-[16px]" strokeWidth={2} /> Correo saliente
        </span>
        {abierto
          ? <ChevronUp aria-hidden="true" className="ml-1 h-[16px] w-[16px]" />
          : <ChevronDown aria-hidden="true" className="ml-1 h-[16px] w-[16px]" />}
      </Button>

      {abierto && (
        <div className="mt-1 flex flex-col gap-2 rounded-card border border-border bg-s2 p-2 sm:max-w-xl" id="correo-saliente">
          <Estado estado={estado} />

          <form className="flex flex-col gap-1" onSubmit={enviar}>
            <Label htmlFor="correo-destino">
              Enviar prueba a <span className="text-t4 text-xs">(opcional)</span>
            </Label>
            <Input
              autoComplete="off"
              className="h-11 sm:h-9"
              id="correo-destino"
              inputMode="email"
              onChange={e => setDestino(e.target.value)}
              placeholder="Vacío: tu propio email"
              type="email"
              value={destino}
            />
            <Button className="h-11 w-full sm:h-9 sm:w-fit" disabled={enviando} type="submit">
              {enviando ? 'Enviando…' : 'Enviar prueba'}
            </Button>
          </form>

          {resultado && (
            <output
              className={`block rounded-card border px-2 py-1 text-sm ${
                resultado.ok ? 'border-success text-success' : 'border-danger text-danger'
              }`}
            >
              {resultado.mensaje}
              {resultado.pista && <span className="mt-1 block text-t3">{resultado.pista}</span>}
            </output>
          )}

          <p className="text-t4 text-xs">
            Se configura con las variables <code>SMTP_HOST</code>, <code>SMTP_USERNAME</code> y{' '}
            <code>SMTP_PASSWORD</code> del backend, o desde la CLI con «Configurar mail». Desde
            acá no se edita y la contraseña nunca se muestra.
          </p>
        </div>
      )}
    </section>
  );
}
