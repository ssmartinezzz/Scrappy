"""MCP tools over the Scrappy REST API.

Curated tools cover the frequent questions and actions; `api_call` reaches the
rest of the API (see `api_reference`). Every tool goes through `ApiClient`,
which refuses anything that deletes data.
"""

from __future__ import annotations

import os
from typing import Any

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from .client import ApiClient, ApiError, ForbiddenOperation

# Enough to answer and to chain into ver_producto / reclasificar; the full row
# carries ML internals and size lists that only burn context.
COMPACT_FIELDS = ("key", "nombre", "precio", "precioOrig", "descuento", "sitio", "marca",
                  "categoria", "sub_categoria", "genero", "rubro", "url", "stock", "vistoEnStockAt",
                  "mejorPromo", "medios")

INSTRUCTIONS = """Scrappy: catalog of Argentine online stores (clothing, gym, supplements, PC hardware,
home). Prices are ARS. Search with buscar_productos, then use a row's `key` with ver_producto.
Rows carry `mejorPromo` and `medios` (payment promos); promos lists them by site or medio.
Mutations (scrape, ML, reclassification, config) are real and affect the shared catalog.
Nothing can be deleted through this server."""


def build_server(api: ApiClient) -> MCPServer:
    mcp = MCPServer("scrappy-catalog", instructions=INSTRUCTIONS)

    def call(method: str, path: str, params: dict | None = None, body: Any = None) -> Any:
        try:
            return api.request(method, path, params=params, json=body)
        except (ApiError, ForbiddenOperation) as e:
            raise ToolError(str(e)) from e

    # ── Catalog ──────────────────────────────────────────────────────────

    @mcp.tool()
    def buscar_productos(q: str | None = None, categoria: list[str] | None = None,
                         sub_categoria: list[str] | None = None, marca: list[str] | None = None,
                         sitio: str | None = None, rubro: str | None = None, genero: str | None = None,
                         talle: list[str] | None = None, precio_min: float | None = None,
                         precio_max: float | None = None, orden: str | None = None,
                         badge: str | None = None, gymrat: bool | None = None,
                         page: int = 0, size: int = 24, cursor: str | None = None,
                         completo: bool = False, stock: str | None = None) -> dict:
        """Search active products (GET /api/data). Filters combine with AND; list filters are OR within.
        orden: precio_asc | precio_desc | nombre | composite | ml_score | desc_pct | mercado_asc | baja_historica.
        genero: hombre | mujer | unisex. Valid categoria/rubro/sitio values come from facetas.
        Next page: pass the returned nextCursor as cursor. completo=True returns full rows.
        stock: todos (default, sold-out rows are flagged in each row's `stock`) | excluir_sin_stock.
        Facets are left out here; use facetas."""
        body = call("GET", "/api/data", {
            "q": q, "categoria": categoria, "subCategoria": sub_categoria, "marca": marca,
            "sitio": sitio, "rubro": rubro, "genero": genero, "talle": talle,
            "precioMin": precio_min, "precioMax": precio_max, "orden": orden, "badge": badge,
            "gymrat": gymrat, "page": page, "size": size, "cursor": cursor, "stock": stock,
        })
        # The page also carries every facet of the catalog (tens of KB); facetas serves those.
        data = body["data"]
        productos = data["productos"] if completo else [
            {k: row[k] for k in COMPACT_FIELDS if k in row} for row in data["productos"]]
        return {"productos": productos, "nextCursor": data.get("nextCursor"), "page": body.get("page")}

    @mcp.tool()
    def ver_producto(key: str) -> dict:
        """A product, its full price history and its current payment promos (`promos`: interest-free
        installments, discounts and surcharges per bank / wallet / card, most useful first), by the
        16-hex `key` from buscar_productos."""
        return call("GET", f"/api/producto/{key}")

    @mcp.tool()
    def promos(sitio: str | None = None, medio: str | None = None, page: int = 0, size: int = 50) -> dict:
        """Current payment promos of the stores (GET /api/promos): cuotas sin interes, descuentos and
        recargos per medio de pago. sitio: site name as in a row's `sitio`. medio: canonical name
        (Santander, Mercado Pago, Transferencia, Visa...); matches the medio or the card network the promo
        requires. Each promo: medio {nombre, tipo}, red {nombre} | null, tipo, cuotas, pct, alcance,
        vigenciaDesde/Hasta, textoFuente, vistoAt."""
        return call("GET", "/api/promos", {"sitio": sitio, "medio": medio, "page": page, "size": size})

    @mcp.tool()
    def facetas() -> dict:
        """Every filter value available (categories, sites, brands, sizes, rubros...)."""
        return call("GET", "/api/facets")

    @mcp.tool()
    def comparar_entre_sitios(q: str | None = None, categoria: str | None = None,
                              rubro: str | None = None, sitio: str | None = None,
                              min_sitios: int = 2, page: int = 0, size: int = 20,
                              stock: str | None = None) -> dict:
        """The same product grouped across stores, to compare prices (GET /api/grupos).
        stock: todos (default) | excluir_sin_stock (drops sold-out offers; each offer has `stock`)."""
        return call("GET", "/api/grupos", {"q": q, "categoria": categoria, "rubro": rubro, "sitio": sitio,
                                           "minSitios": min_sitios, "page": page, "size": size,
                                           "stock": stock})

    @mcp.tool()
    def mejores(categoria: str | None = None, rubro: str | None = None, genero: str | None = None,
                gymrat: bool | None = None, cursor: str | None = None, limit: int = 20) -> dict:
        """Best picks. Without categoria: an index of categories (count, median price), no picks.
        With categoria: that category's products ranked best first, keyset-paged with `cursor`."""
        if categoria:
            return call("GET", "/api/mejores/picks", {"categoria": categoria, "rubro": rubro, "genero": genero,
                                                      "gymrat": gymrat, "cursor": cursor, "limit": limit})
        # The raw answer is every pick of ~280 categories (~4 MB); even 3 picks each is
        # 225 KB. The index is what fits a turn; picks come from the categoria branch.
        body = call("GET", "/api/mejores", {"rubro": rubro, "gymrat": gymrat, "genero": genero})
        return {"data": [{k: c.get(k) for k in ("categoria", "count", "rubro", "mediana")} for c in body["data"]]}

    @mcp.tool()
    def oportunidades_cuotas(url: str) -> dict:
        """Whether paying a product in installments beats paying cash, given inflation (GET /api/recomendacion)."""
        return call("GET", "/api/recomendacion", {"url": url})

    @mcp.tool()
    def tendencias() -> dict:
        """ML pipeline output: trends, real discounts, segments."""
        return call("GET", "/api/tendencias")

    @mcp.tool()
    def estado() -> dict:
        """Scraper status, ML state and any interrupted run left by the previous process."""
        return {
            "scraper": call("GET", "/api/status"),
            "ml": call("GET", "/api/ml/estado"),
            "corrida_interrumpida": call("GET", "/api/scrape/interrupted"),
        }

    @mcp.tool()
    def sitios() -> dict:
        """Configured and dynamic sites."""
        return call("GET", "/api/sitios")

    # ── Builders ─────────────────────────────────────────────────────────

    @mcp.tool()
    def armar_pc(presupuesto: float, opciones: dict[str, Any] | None = None) -> dict:
        """Compatible PC build for a budget in ARS (GET /api/pcs/builder). opciones takes any of:
        conGpu, gama, ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi, capacidadMinimaGb,
        tamanioGabinete, tipoCooler, wattsMinimos, ramMinimaGb, uso, modo, excluir, incluirSinStock.
        Sold-out parts are skipped unless incluirSinStock is true; unknown stock stays eligible."""
        return call("GET", "/api/pcs/builder", {"presupuesto": presupuesto, **(opciones or {})})

    @mcp.tool()
    def armar_outfit(presupuesto: float, categorias: list[str], genero: str | None = None,
                     estilo: str | None = None, excluir: list[str] | None = None,
                     opciones: dict[str, Any] | None = None) -> dict:
        """Budget-optimal outfit (GET /api/outfits/builder). categorias: one pick per category, e.g.
        ["Remera", "Short", "Zapatilla"]. presupuesto must be > 0. estilo defaults to gym.
        excluir: product urls to leave out. opciones: pin, greedy, incluirSinStock (sold-out items are
        skipped by default)."""
        return call("GET", "/api/outfits/builder", {
            "presupuesto": presupuesto, "categorias": ",".join(categorias), "genero": genero,
            "estilo": estilo, "excluir": _csv(excluir), **(opciones or {})})

    @mcp.tool()
    def armar_suplementos(tipos: list[str], presupuesto: float = 0, excluir: list[str] | None = None,
                          incluir_sin_stock: bool | None = None) -> dict:
        """Supplement combo (GET /api/suplementos/builder). tipos e.g. ["Creatina", "Proteína en Polvo"];
        valid values: api_call GET /api/suplementos/tipos. presupuesto 0 = no cap.
        Sold-out products are skipped unless incluir_sin_stock is true."""
        return call("GET", "/api/suplementos/builder", {"tipos": ",".join(tipos), "presupuesto": presupuesto,
                                                        "excluir": _csv(excluir),
                                                        "incluirSinStock": incluir_sin_stock})

    # ── Actions (real, shared effects) ───────────────────────────────────

    @mcp.tool()
    def lanzar_scrape(sitios: list[str] | None = None, precio_min: float | None = None,
                      precio_max: float | None = None, force_retrain: bool | None = None) -> dict:
        """Start a scrape in the background (all sites when sitios is empty). Takes minutes; follow it with estado."""
        return call("POST", "/api/scrape", {"sitios": sitios, "precioMin": precio_min,
                                            "precioMax": precio_max, "forceRetrain": force_retrain})

    @mcp.tool()
    def cancelar_scrape() -> dict:
        """Ask the running scrape to stop. Idempotent."""
        return call("POST", "/api/scrape/cancel")

    @mcp.tool()
    def retomar_scrape() -> dict:
        """Resume the interrupted run: only the sites still missing."""
        return call("POST", "/api/scrape/resume")

    @mcp.tool()
    def reclasificar(url: str, categoria_propuesta: str, sub_categoria_propuesta: str | None = None,
                     marca_propuesta: str | None = None, genero_propuesto: str | None = None,
                     categoria_actual: str | None = None, nombre_producto: str | None = None) -> dict:
        """Change a product's category (and optionally sub-category, brand, gender). The server re-validates
        against the taxonomy (POST /api/agent/apply). Confirm with the user before calling."""
        body = {"url": url, "categoriaPropuesta": categoria_propuesta,
                "subCategoriaPropuesta": sub_categoria_propuesta, "marcaPropuesta": marca_propuesta,
                "generoPropuesto": genero_propuesto, "categoriaActual": categoria_actual,
                "nombreProducto": nombre_producto}
        return call("POST", "/api/agent/apply", {}, {k: v for k, v in body.items() if v is not None})

    # ── Everything else ──────────────────────────────────────────────────

    @mcp.tool()
    def api_reference() -> str:
        """The backend's OpenAPI contract: every path, its parameters and required role."""
        return call("GET", "/api/openapi.yaml")

    @mcp.tool()
    def api_call(method: str, path: str, params: dict[str, Any] | None = None,
                 body: dict[str, Any] | list | None = None) -> Any:
        """Any other backend endpoint (GET/POST/PUT/PATCH on /api/*). DELETE and discarding an interrupted
        run are refused. Check api_reference for paths and parameters."""
        return call(method, path, params, body)

    return mcp


def _csv(values: list[str] | None) -> str | None:
    """The builders take comma-separated lists, not repeated query params."""
    return ",".join(values) if values else None


def main() -> None:
    try:
        user, password = os.environ["SCRAPPY_API_USER"], os.environ["SCRAPPY_API_PASSWORD"]
    except KeyError as e:
        raise SystemExit(f"missing environment variable {e}; see docs/MCP.md") from None
    api = ApiClient(os.environ.get("SCRAPPY_API_URL") or "http://localhost:3000", user, password)
    build_server(api).run()


if __name__ == "__main__":
    main()
