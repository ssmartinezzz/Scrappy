-- V137__fursten_shopify.sql
--
-- Fursten left Tiendanube: www.fursten.com.ar/productos/ now redirects to fursten.com.ar, a Shopify storefront
-- (cdn.shopify.com, Shopify.theme). Only the platform changes; the row keeps its origin, monomarca, rubro_forzado
-- and es_premium. The old Tiendanube rows are not mapped to the new handles: the slugs differ, so a match would
-- be a guess. The sweep of the first successful Shopify run retires them. Idempotent.
UPDATE sitio SET plataforma = 'shopify' WHERE sitio_key = 'fursten';
