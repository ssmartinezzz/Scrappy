// frontend-auth-ui, Phase 5 (design D6/D7 file table). Route-level role gate.
// Created here alongside AuthGate; wiring it around /cronjobs (and any other
// ADMIN-only route) is Phase 7's job (tasks-part2 7.8) once role-aware nav
// exists — this component is self-contained and usable the moment that lands.
import { useAuth } from './AuthProvider';
import AccessDenied from '../pages/AccessDenied';

export default function RequireRole({ role, children }) {
  const { identity } = useAuth();
  const roles = identity?.roles || [];
  if (!roles.includes(role)) return <AccessDenied />;
  return children;
}
