import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

import PicksPanel from '@/components/PicksPanel';

function Ruta() {
  return <output data-testid="ruta">{useLocation().pathname}</output>;
}

// PicksPanel reads its step from the URL, so every test mounts it under the same `picks/*` route App.jsx declares.
export function renderPicks(ruta = '/picks', props = {}) {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <Routes>
        <Route path="/picks/*" element={<><PicksPanel {...props} /><Ruta /></>} />
      </Routes>
    </MemoryRouter>
  );
}
