// frontend-auth-ui, Phase 5 (design D6). An explicit screen for a permission
// gap — never a silent redirect (spec: "Deep link to an ADMIN route is
// explicit, not silent"). Reused both by RequireRole (Phase 7 route gating)
// and anywhere a 403 needs a permissions-denied UI instead of a refresh loop.
import { ShieldAlert } from 'lucide-react';

export default function AccessDenied() {
  return (
    <div role="alert" className="flex h-full flex-col items-center justify-center gap-3 p-8 text-center text-t2">
      <ShieldAlert className="size-10 text-danger" />
      <h1 className="text-xl font-semibold text-t1">Acceso denegado</h1>
      <p className="max-w-sm text-sm text-t3">
        No tenés permiso para ver esta sección. Si creés que es un error, pedile
        a un administrador que revise tu rol.
      </p>
    </div>
  );
}
