// frontend-auth-ui, Phase 7 (design D6, tasks-part2 7.1). visibleNav is the
// single source of truth both NavMenubar and NavDrawer render from — pure
// function, no React, so it's tested directly.
import { describe, expect, it } from 'vitest';

import { NAV_CONFIG, visibleNav } from './nav-config';

describe('visibleNav — role-based filtering (design D6)', () => {
  it('drops a link node whose `requires` role is absent from `roles`', () => {
    const config = [
      { kind: 'link', label: 'Catálogo', to: '/catalogo' },
      { kind: 'link', label: 'Cronjobs', to: '/cronjobs', requires: 'ADMIN' },
    ];

    const result = visibleNav(config, ['VIEWER']);

    expect(result.map(n => n.label)).toEqual(['Catálogo']);
  });

  it('keeps a link node whose `requires` role IS present', () => {
    const config = [
      { kind: 'link', label: 'Cronjobs', to: '/cronjobs', requires: 'ADMIN' },
    ];

    const result = visibleNav(config, ['ADMIN']);

    expect(result.map(n => n.label)).toEqual(['Cronjobs']);
  });

  it('keeps a link node with no `requires` at all, regardless of roles', () => {
    const config = [{ kind: 'link', label: 'Catálogo', to: '/catalogo' }];

    expect(visibleNav(config, []).map(n => n.label)).toEqual(['Catálogo']);
  });

  it('drops an entire `menu` node when every one of its items filters out (empty-dropdown guard)', () => {
    const config = [
      {
        kind: 'menu', label: 'Guardados',
        items: [{ label: 'Solo admin', to: '/x', requires: 'ADMIN' }],
      },
    ];

    expect(visibleNav(config, ['VIEWER'])).toEqual([]);
  });

  it('keeps a `menu` node but filters its items when only SOME items require a missing role', () => {
    const config = [
      {
        kind: 'menu', label: 'Análisis',
        items: [
          { label: 'Mercado', to: '/analisis/mercado' },
          { label: 'Solo admin', to: '/x', requires: 'ADMIN' },
        ],
      },
    ];

    const result = visibleNav(config, ['VIEWER']);

    expect(result).toHaveLength(1);
    expect(result[0].items.map(i => i.label)).toEqual(['Mercado']);
  });

  it('the real NAV_CONFIG hides Cronjobs for a VIEWER and shows it for an ADMIN', () => {
    const viewerLabels = visibleNav(NAV_CONFIG, ['VIEWER']).map(n => n.label);
    const adminLabels = visibleNav(NAV_CONFIG, ['ADMIN']).map(n => n.label);

    expect(viewerLabels).not.toContain('Cronjobs');
    expect(adminLabels).toContain('Cronjobs');
  });

  it('the real NAV_CONFIG hides Cuentas for a VIEWER and shows it for an ADMIN', () => {
    const viewerLabels = visibleNav(NAV_CONFIG, ['VIEWER']).map(n => n.label);
    const adminLabels = visibleNav(NAV_CONFIG, ['ADMIN']).map(n => n.label);

    expect(viewerLabels).not.toContain('Cuentas');
    expect(adminLabels).toContain('Cuentas');
  });

  // apidocs-public-filtered-document: the console has NO nav entry, for any
  // role. It is reachable by typing /apidocs and nothing else, and the
  // document it renders is filtered server-side, so there is nothing left for
  // a nav-visibility rule to protect.
  it('the real NAV_CONFIG offers no entry point to /apidocs, for any role', () => {
    const destinos = roles => visibleNav(NAV_CONFIG, roles)
      .flatMap(n => (n.kind === 'link' ? [n.to] : n.items.map(i => i.to)));

    expect(destinos(['VIEWER'])).not.toContain('/apidocs');
    expect(destinos(['ADMIN'])).not.toContain('/apidocs');
  });

  // Un `requires` que se cae del literal deja el link visible para todos, y la
  // aserción por label de arriba seguiría pasando porque un ADMIN igual lo ve.
  it('every admin destination in the real NAV_CONFIG is gated behind ADMIN', () => {
    const rutasAdmin = ['/cronjobs', '/admin/manage/users'];
    const visiblesParaViewer = visibleNav(NAV_CONFIG, ['VIEWER'])
      .flatMap(n => (n.kind === 'link' ? [n.to] : n.items.map(i => i.to)));

    for (const ruta of rutasAdmin) {
      expect(visiblesParaViewer).not.toContain(ruta);
    }
  });
});
