import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import FavoritosPanel from '@/components/FavoritosPanel';

// nav-guardados-armadores (D1/D2/D6): /armadores se borró y sus dos colecciones
// se mudaron acá. Una PC guardada se trata EXACTAMENTE como un outfit: slide de
// colección en el carrusel y tarjeta con renombrar/eliminar en la lista. Este
// archivo es el contrato de ArmadoresPanel.test.jsx portado, más lo que
// /favoritos ya hacía con los productos.

const outfit = (id, nombre = 'Outfit 1') => ({
  id, nombre, totalEstimado: 50000, createdAt: '2026-09-18T00:00:00Z',
  slots: [{ nombre: 'Remera negra', img: 'https://img/r.jpg', sitio: 'freres', precio: 25000 }],
});

const pc = (id, nombre = 'PC 1') => ({
  id, nombre, presupuesto: 0, conGpu: false, totalEstimado: 100000,
  createdAt: '2026-09-18T00:00:00Z',
  picks: [{ slot: 'mother', sitio: 'rockethard', nombre: 'B550M', precio: 100000, url: 'https://x', img: 'https://img/m.jpg' }],
});

const favorito = (url, nombre = 'Remera') => ({
  url, nombre, sitio: 'freres', precio: 25000, img: 'https://img/x.jpg', descontinuado: false,
});

const lista = async user => user.click(screen.getByLabelText('Vista lista'));

describe('FavoritosPanel — estados vacíos', () => {
  it('nombra las tres cosas que se pueden guardar cuando no hay ninguna', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[]} />);

    expect(screen.getByText(/no marcaste productos como favoritos/i)).toBeInTheDocument();
    expect(screen.getByText(/no guardaste ningún outfit/i)).toBeInTheDocument();
    expect(screen.getByText(/no guardaste ning[uú]n(a)? pc/i)).toBeInTheDocument();
  });

  it('con outfits o PCs guardadas hay carrusel, aunque no haya un solo producto favorito', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(1, 'Outfit A')]} savedPcs={[pc(1, 'PC A')]} />);

    expect(screen.getByLabelText('Outfit A')).toBeInTheDocument();
    expect(screen.getByLabelText('PC A')).toBeInTheDocument();
  });
});

describe('FavoritosPanel — carrusel: una PC es un slide de colección, igual que un outfit', () => {
  it('pone un slide por producto, por outfit y por PC', () => {
    render(<FavoritosPanel favoritos={[favorito('https://a', 'Remera A')]}
      savedOutfits={[outfit(1, 'Outfit A')]} savedPcs={[pc(1, 'PC A')]} />);

    expect(screen.getByLabelText('Remera A')).toBeInTheDocument();
    expect(screen.getByLabelText('Outfit A')).toBeInTheDocument();
    expect(screen.getByLabelText('PC A')).toBeInTheDocument();
  });

  it('el slide de una PC declara aria-expanded, igual que el de un outfit', () => {
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(1, 'Outfit A')]} savedPcs={[pc(1, 'PC A')]} />);

    expect(screen.getByLabelText('Outfit A')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByLabelText('PC A')).toHaveAttribute('aria-expanded', 'false');
  });

  it('activar el slide de una PC abre la tira con sus componentes', async () => {
    const user = userEvent.setup();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[pc(1, 'PC A')]} />);

    // El carrusel activa en UN click si el slide ya está centrado, y en dos si
    // hay que traerlo al frente primero (el paso de preview del coverflow).
    // Con una sola PC, es el slide centrado.
    const slide = screen.getByLabelText('PC A');
    await user.click(slide);

    expect(slide).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('region', { name: /PC A/ })).toBeInTheDocument();
    expect(screen.getByText('B550M')).toBeInTheDocument();
  });

  it('abrir una colección cierra la otra: la tira es una sola', async () => {
    const user = userEvent.setup();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(1, 'Outfit A')]} savedPcs={[pc(1, 'PC A')]} />);

    const slideOutfit = screen.getByLabelText('Outfit A'); // centrado: un click
    await user.click(slideOutfit);
    expect(slideOutfit).toHaveAttribute('aria-expanded', 'true');

    const slidePc = screen.getByLabelText('PC A'); // fuera de centro: dos
    await user.click(slidePc);
    await user.click(slidePc);

    expect(slidePc).toHaveAttribute('aria-expanded', 'true');
    expect(slideOutfit).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('FavoritosPanel — lista', () => {
  it('renderiza una SavedOutfitCard por outfit y una SavedPcCard por PC', async () => {
    const user = userEvent.setup();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(1, 'Outfit A'), outfit(2, 'Outfit B')]}
      savedPcs={[pc(1, 'PC A')]} />);
    await lista(user);

    expect(screen.getByText('Outfit A')).toBeInTheDocument();
    expect(screen.getByText('Outfit B')).toBeInTheDocument();
    expect(screen.getByText('PC A')).toBeInTheDocument();
    expect(screen.getByText('B550M')).toBeInTheDocument();
  });

  it('el contador del header cuenta SOLO productos, no outfits ni PCs', () => {
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
    await lista(user);

    await user.click(screen.getByTitle('Eliminar outfit'));

    expect(onDeleteSavedOutfit).toHaveBeenCalledWith(7);
  });

  it('renombrar un outfit llama a onRenameSavedOutfit con su id y el nuevo nombre', async () => {
    const user = userEvent.setup();
    const onRenameSavedOutfit = vi.fn();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[outfit(7, 'Outfit A')]} savedPcs={[]}
      onRenameSavedOutfit={onRenameSavedOutfit} />);
    await lista(user);

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
    await lista(user);

    await user.click(screen.getByTitle('Eliminar PC'));

    expect(onDeleteSavedPc).toHaveBeenCalledWith(9);
  });

  it('renombrar un PC llama a onRenameSavedPc con su id y el nuevo nombre', async () => {
    const user = userEvent.setup();
    const onRenameSavedPc = vi.fn();
    render(<FavoritosPanel favoritos={[]} savedOutfits={[]} savedPcs={[pc(9, 'PC A')]}
      onRenameSavedPc={onRenameSavedPc} />);
    await lista(user);

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
    await lista(user);

    await user.click(screen.getByTitle('Quitar de favoritos'));

    expect(onDeleteFavorito).toHaveBeenCalledWith('https://a');
  });
});
