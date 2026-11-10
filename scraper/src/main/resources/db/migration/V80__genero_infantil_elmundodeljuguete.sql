-- V80__genero_infantil_elmundodeljuguete.sql
--
-- NormalizerService now files every El Mundo del Juguete product as infantil: a toy store sells to kids,
-- and its blank-genero backpacks, slippers and dolls' dresses were filling the adult /picks (dev,
-- 2026-11-10: 494 of 969 Mochila rows). This aligns the rows already stored instead of waiting for the
-- next scrape, which stays the source of truth. Never a row locked by hand (V3). Idempotent: a handled
-- row is already 'infantil' and no longer matches its own WHERE.
UPDATE productos
   SET genero = 'infantil'
 WHERE sitio_key = 'elmundodeljuguete'
   AND bloqueado_por IS NULL
   AND genero IS DISTINCT FROM 'infantil';
