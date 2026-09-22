import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import FavoritosPanel from '@/components/FavoritosPanel';

// nav-guardados-armadores (D1/D2): /armadores se borró y sus dos secciones se
// mudaron acá tal cual — mismas tarjetas, mismos callbacks, mismos textos
// vacíos. Este archivo es el contrato de ArmadoresPanel.test.jsx portado, más
// lo que /favoritos ya hacía con los productos.

const outfit = (id, nombre = 'Outfit 1') => ({
  id, nombre, slots: [], totalEstimado: 50000, createdAt: '2026-09-18T00:00:00Z',
});

const pc = (id, nombre = 'PC 1') => ({
  id, nombre, presupuesto: 0, conGpu: false, totalEstimado: 100000,
  createdAt: '2026-09-18T00:00:00Z',
  picks: [{ slot: 'mother', sitio: 'rockethard', nombre: 'B550M', precio: 100000, url: 'https://x' }],
});

const favorito = (url, nombre = 'Remera') => ({
  url, nombre, sitio: 'freres', precio: 25000, img: 'https://img/x.jpg', descontinuado: false,
});

describe('FavoritosPanel — estados vacíos', () => {
  it('muestra los tres mensajes vacíos cuando no hay nada guardado', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[]} />);

    expect(screen.getByText(/no marcaste productos como favoritos/i)).toBeInTheDocument();
    expect(screen.getByText(/no guardaste ningún outfit/i)).toBeInTheDocument();
    expect(screen.getByText(/no guardaste ning[uú]n(a)? pc/i)).toBeInTheDocument();
  });

  it('las secciones de guardados se muestran aunque no haya ningún producto favorito', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(1, 'Outfit A')]} savedPcs={[pc(1, 'PC A')]} />);

    expect(screen.getByText('Outfit A')).toBeInTheDocument();
    expect(screen.getByText('PC A')).toBeInTheDocument();
  });
});

describe('FavoritosPanel — listas', () => {
  it('renderiza una SavedOutfitCard por outfit guardado', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(1, 'Outfit A'), outfit(2, 'Outfit B')]} savedPcs={[]} />);

    expect(screen.getByText('Outfit A')).toBeInTheDocument();
    expect(screen.getByText('Outfit B')).toBeInTheDocument();
    expect(screen.queryByText(/no guardaste ningún outfit/i)).not.toBeInTheDocument();
  });

  it('renderiza una SavedPcCard por PC guardada', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[pc(1, 'PC A'), pc(2, 'PC B')]} />);

    expect(screen.getByText('PC A')).toBeInTheDocument();
    expect(screen.getByText('PC B')).toBeInTheDocument();
    expect(screen.getAllByText('B550M')).toHaveLength(2);
  });

  it('el contador del header sigue contando SOLO productos, no outfits ni PCs', () => {
    render(<FavoritosPanel favoritos={[favorito('https://a')]} savedOutfits={[outfit(1), outfit(2)]} savedPcs={[pc(1)]} />);

    expect(screen.getByText('1 producto guardado')).toBeInTheDocument();
  });
});

describe('FavoritosPanel — acciones', () => {
  it('eliminar un outfit llama a onDeleteSavedOutfit con su id', async () => {
    const user = userEvent.setup();
    const onDeleteSavedOutfit = vi.fn();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(7, 'Outfit A')]} savedPcs={[]}
      onDeleteSavedOutfit={onDeleteSavedOutfit} />);

    await user.click(screen.getByTitle('Eliminar outfit'));

    expect(onDeleteSavedOutfit).toHaveBeenCalledWith(7);
  });

  it('renombrar un outfit llama a onRenameSavedOutfit con su id y el nuevo nombre', async () => {
    const user = userEvent.setup();
    const onRenameSavedOutfit = vi.fn();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(7, 'Outfit A')]} savedPcs={[]}
      onRenameSavedOutfit={onRenameSavedOutfit} />);

    await user.dblClick(screen.getByText('Outfit A'));
    const input = screen.getByDisplayValue('Outfit A');
    await user.clear(input);
    await user.type(input, 'Nuevo nombre{Enter}');

    expect(onRenameSavedOutfit).toHaveBeenCalledWith(7, 'Nuevo nombre');
  });

  it('eliminar un PC llama a onDeleteSavedPc con su id', async () => {
    const user = userEvent.setup();
    const onDeleteSavedPc = vi.fn();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[pc(9, 'PC A')]}
      onDeleteSavedPc={onDeleteSavedPc} />);

    await user.click(screen.getByTitle('Eliminar PC'));

    expect(onDeleteSavedPc).toHaveBeenCalledWith(9);
  });

  it('renombrar un PC llama a onRenameSavedPc con su id y el nuevo nombre', async () => {
    const user = userEvent.setup();
    const onRenameSavedPc = vi.fn();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[pc(9, 'PC A')]}
      onRenameSavedPc={onRenameSavedPc} />);

    await user.dblClick(screen.getByText('PC A'));
    const input = screen.getByDisplayValue('PC A');
    await user.clear(input);
    await user.type(input, 'Nuevo nombre{Enter}');

    expect(onRenameSavedPc).toHaveBeenCalledWith(9, 'Nuevo nombre');
  });

  it('quitar un producto favorito sigue llamando a onDeleteFavorito con su url', async () => {
    const user = userEvent.setup();
    const onDeleteFavorito = vi.fn();
    render(<FavoritosPanel favoritos={[favorito('https://a', 'Remera A')]} savedOutfits={[]} savedPcs={[]}
      onDeleteFavorito={onDeleteFavorito} />);

    // La vista de lista es la que expone el botón de quitar; el default es
    // carrusel, así que se cambia primero.
    await user.click(screen.getByLabelText('Vista lista'));
    await user.click(screen.getByTitle('Quitar de favoritos'));

    expect(onDeleteFavorito).toHaveBeenCalledWith('https://a');
  });
});
