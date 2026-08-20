// frontend-auth-ui, Phase 5 (design D5). Sits ABOVE the app's <Routes> so
// RootGate/SplashRoute — which call fetchStatus(), an AUTHENTICATED endpoint
// (ApiRoutePolicy.java:150) — cannot mount before auth has settled. Does NOT
// edit RootGate itself; it just stops it mounting early.
import { Navigate, useLocation } from 'react-router-dom';

import { useAuth } from './AuthProvider';
import RouteFallback from '../components/RouteFallback';

// frontend-auth-ui Phase 6: /forgot-password and /reset-password are public
// too — a reset link is exactly what an anonymous, locked-out visitor uses.
const PUBLIC_ROUTES = ['/login', '/forgot-password', '/reset-password'];

export default function AuthGate({ children }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'booting') return <RouteFallback />;

  if (status === 'anonymous' && !PUBLIC_ROUTES.includes(location.pathname)) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return children;
}
