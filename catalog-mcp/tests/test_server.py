import json

import pytest
from mcp import Client

from scrappy_mcp.server import build_server

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
def server(client):
    return build_server(client)


async def call(server, tool, args=None):
    async with Client(server) as c:
        result = await c.call_tool(tool, args or {})
    text = result.content[0].text if result.content else ""
    return result, text


def sent(backend):
    """The last request that was not a login."""
    return [r for r in backend.requests if r.url.path != "/api/auth/login"][-1]


async def test_no_tool_can_delete(server):
    async with Client(server) as c:
        tools = (await c.list_tools()).tools
    names = {t.name for t in tools}

    assert {"buscar_productos", "ver_producto", "armar_pc", "lanzar_scrape", "reclasificar", "api_call"} <= names
    assert not [n for n in names if any(w in n for w in ("borrar", "eliminar", "delete", "descartar"))]


async def test_buscar_productos_maps_filters_to_the_api_names(server, backend):
    await call(server, "buscar_productos", {
        "q": "buzo", "categoria": ["Buzo"], "precio_min": 1000, "precio_max": 50000,
        "orden": "precio_desc", "sitio": "Vaypol", "size": 5,
    })

    req = sent(backend)
    assert req.method == "GET" and req.url.path == "/api/data"
    assert dict(req.url.params) == {"q": "buzo", "categoria": "Buzo", "precioMin": "1000.0",
                                    "precioMax": "50000.0", "orden": "precio_desc",
                                    "sitio": "Vaypol", "size": "5", "page": "0"}


REAL_DATA_SHAPE = {  # captured from GET /api/data on 2026-11-13, trimmed
    "data": {
        "meta": {"moneda": "ARS", "facets": {"talles": {"S": 5893}}, "marcas": ["Nike"]},
        "productos": [{"key": "abc", "nombre": "Buzo", "precio": 100.0, "sitio": "X", "url": "u",
                       "sub_categoria": "Canguro", "img": "i", "ml": {"scoreP": 1}, "talles": ["S"],
                       "senal": {}}],
        "nextCursor": "CUR",
    },
    "page": {"number": 0, "totalElements": 1},
}


async def test_buscar_productos_compacts_rows_and_drops_the_facets(server, backend):
    backend.reply = REAL_DATA_SHAPE

    _, text = await call(server, "buscar_productos", {"q": "buzo"})

    assert json.loads(text) == {
        "productos": [{"key": "abc", "nombre": "Buzo", "precio": 100.0, "sitio": "X",
                       "sub_categoria": "Canguro", "url": "u"}],
        "nextCursor": "CUR",
        "page": {"number": 0, "totalElements": 1},
    }


async def test_buscar_productos_compact_rows_keep_the_stock_state(server, backend):
    backend.reply = {"data": {"productos": [{"key": "k", "nombre": "SSD", "precio": 1.0, "stock": "sin_stock",
                                             "vistoEnStockAt": "2026-11-10T12:00:00Z",
                                             "stockChequeadoAt": "2026-11-13T08:00:00Z", "enStock": False}]},
                     "page": {}}

    _, text = await call(server, "buscar_productos", {"q": "ssd"})

    row = json.loads(text)["productos"][0]
    assert row["stock"] == "sin_stock" and row["vistoEnStockAt"] == "2026-11-10T12:00:00Z"
    assert "enStock" not in row


async def test_buscar_productos_compact_rows_keep_the_promos(server, backend):
    backend.reply = {"data": {"productos": [{"key": "k", "nombre": "SSD", "precio": 1.0, "talles": ["S"],
                                             "medios": ["Mercado Pago", "Santander"],
                                             "mejorPromo": {"medio": "Mercado Pago", "red": None,
                                                            "tipo": "descuento", "cuotas": None, "pct": 15.0}}]},
                     "page": {}}

    _, text = await call(server, "buscar_productos", {"q": "ssd"})

    row = json.loads(text)["productos"][0]
    assert row["medios"] == ["Mercado Pago", "Santander"]
    assert row["mejorPromo"]["pct"] == 15.0
    assert "talles" not in row


async def test_promos_lists_by_site_and_medio(server, backend):
    await call(server, "promos", {"sitio": "Sporting", "medio": "Visa", "size": 10})

    req = sent(backend)
    assert req.method == "GET" and req.url.path == "/api/promos"
    assert dict(req.url.params) == {"sitio": "Sporting", "medio": "Visa", "size": "10", "page": "0"}


async def test_promos_without_filters_sends_only_the_paging(server, backend):
    await call(server, "promos")

    assert dict(sent(backend).url.params) == {"page": "0", "size": "50"}


async def test_buscar_productos_forwards_the_stock_filter(server, backend):
    await call(server, "buscar_productos", {"q": "ssd", "stock": "excluir_sin_stock"})

    assert sent(backend).url.params["stock"] == "excluir_sin_stock"


async def test_buscar_productos_omits_stock_when_not_asked(server, backend):
    await call(server, "buscar_productos", {"q": "ssd"})

    assert "stock" not in sent(backend).url.params


async def test_comparar_entre_sitios_forwards_the_stock_filter(server, backend):
    await call(server, "comparar_entre_sitios", {"q": "ssd", "stock": "excluir_sin_stock"})

    assert sent(backend).url.params["stock"] == "excluir_sin_stock"


async def test_buscar_productos_completo_keeps_full_rows(server, backend):
    backend.reply = REAL_DATA_SHAPE

    _, text = await call(server, "buscar_productos", {"q": "buzo", "completo": True})

    assert json.loads(text)["productos"][0]["talles"] == ["S"]


async def test_buscar_productos_forwards_the_cursor(server, backend):
    await call(server, "buscar_productos", {"cursor": "CUR"})

    assert sent(backend).url.params["cursor"] == "CUR"


async def test_ver_producto_hits_the_detail_endpoint(server, backend):
    await call(server, "ver_producto", {"key": "0123456789abcdef"})

    assert sent(backend).url.path == "/api/producto/0123456789abcdef"


async def test_armar_pc_passes_preferences_through(server, backend):
    await call(server, "armar_pc", {"presupuesto": 1_000_000, "opciones": {"gama": "media", "conGpu": True}})

    req = sent(backend)
    assert req.url.path == "/api/pcs/builder"
    assert dict(req.url.params) == {"presupuesto": "1000000.0", "gama": "media", "conGpu": "true"}


async def test_armar_pc_forwards_incluir_sin_stock(server, backend):
    await call(server, "armar_pc", {"presupuesto": 1_000_000, "opciones": {"incluirSinStock": True}})

    assert sent(backend).url.params["incluirSinStock"] == "true"


async def test_armar_suplementos_forwards_incluir_sin_stock_only_when_asked(server, backend):
    await call(server, "armar_suplementos", {"tipos": ["Creatina"]})
    assert "incluirSinStock" not in sent(backend).url.params

    await call(server, "armar_suplementos", {"tipos": ["Creatina"], "incluir_sin_stock": True})
    assert sent(backend).url.params["incluirSinStock"] == "true"


async def test_armar_outfit_forwards_incluir_sin_stock(server, backend):
    await call(server, "armar_outfit", {"presupuesto": 100000, "categorias": ["Short"],
                                        "opciones": {"incluirSinStock": True}})

    assert sent(backend).url.params["incluirSinStock"] == "true"


async def test_lanzar_scrape_posts_the_selected_sites(server, backend):
    await call(server, "lanzar_scrape", {"sitios": ["Vaypol", "Easy"]})

    req = sent(backend)
    assert req.method == "POST" and req.url.path == "/api/scrape"
    assert req.url.params.get_list("sitios") == ["Vaypol", "Easy"]


async def test_reclasificar_posts_the_proposal(server, backend):
    await call(server, "reclasificar", {"url": "https://x/p", "categoria_propuesta": "Calza",
                                        "sub_categoria_propuesta": "Biker"})

    req = sent(backend)
    assert req.method == "POST" and req.url.path == "/api/agent/apply"
    assert json.loads(req.content) == {"url": "https://x/p", "categoriaPropuesta": "Calza",
                                       "subCategoriaPropuesta": "Biker"}


async def test_api_call_reaches_any_allowed_endpoint(server, backend):
    _, text = await call(server, "api_call", {"method": "PUT", "path": "/api/config",
                                              "body": {"precioMinimo": 1}})

    assert json.loads(text)["data"]["body"] == {"precioMinimo": 1}
    assert sent(backend).method == "PUT"


@pytest.mark.parametrize("method,path", [("DELETE", "/api/db/productos"), ("POST", "/api/scrape/discard")])
async def test_api_call_refuses_deletion_without_sending(server, backend, method, path):
    result, text = await call(server, "api_call", {"method": method, "path": path})

    assert result.is_error
    assert "not allowed" in text
    assert backend.requests == []


async def test_api_errors_surface_as_tool_errors(server, backend):
    result, text = await call(server, "api_call", {"method": "POST", "path": "/api/boom"})

    assert result.is_error
    assert "409" in text


async def test_unreachable_backend_says_so():
    import httpx
    from scrappy_mcp.client import ApiClient

    def refuse(request):
        raise httpx.ConnectError("connection refused", request=request)

    down = ApiClient("http://api.test", "bot", "pw", transport=httpx.MockTransport(refuse))
    result, text = await call(build_server(down), "facetas")

    assert result.is_error
    assert "http://api.test" in text and "not reachable" in text


async def test_armar_outfit_requires_categorias_and_sends_them_comma_separated(server, backend):
    result, _ = await call(server, "armar_outfit", {"presupuesto": 150000})
    assert result.is_error and backend.requests == []

    await call(server, "armar_outfit", {"presupuesto": 150000, "categorias": ["Remera", "Short"],
                                        "excluir": ["u1", "u2"], "estilo": "gym"})

    assert dict(sent(backend).url.params) == {"presupuesto": "150000.0", "categorias": "Remera,Short",
                                              "excluir": "u1,u2", "estilo": "gym"}


async def test_armar_suplementos_requires_tipos_and_sends_them_comma_separated(server, backend):
    result, _ = await call(server, "armar_suplementos", {"presupuesto": 80000})
    assert result.is_error and backend.requests == []

    await call(server, "armar_suplementos", {"tipos": ["Creatina", "Proteína en Polvo"]})

    assert dict(sent(backend).url.params) == {"tipos": "Creatina,Proteína en Polvo", "presupuesto": "0"}


async def test_mejores_without_category_is_a_summary_not_every_pick(server, backend):
    pick = {"tipo": "top", "label": "Buena compra", "nombre": "N", "precio": 1.0, "sitio": "S",
            "key": "k", "url": "u", "img": "i", "scoreP": 9, "badge": "b"}
    backend.reply = {"data": [{"categoria": "Remera", "count": 4242, "rubro": "indumentaria",
                               "imgCat": "x", "mediana": 48999, "picks": [pick] * 30}]}

    _, text = await call(server, "mejores")

    row = json.loads(text)["data"][0]
    assert row == {"categoria": "Remera", "count": 4242, "rubro": "indumentaria", "mediana": 48999}
