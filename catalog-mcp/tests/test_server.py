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


async def test_buscar_productos_compacts_each_row(server, backend):
    full = {"key": "abc", "nombre": "Buzo", "precio": 100.0, "sitio": "X", "url": "u",
            "img": "i", "ml": {"scoreP": 1}, "talles": ["S"], "senal": {}}
    backend.reply = {"data": [full], "page": {"number": 0, "totalElements": 1}}

    _, text = await call(server, "buscar_productos", {"q": "buzo"})

    body = json.loads(text)
    assert body["data"] == [{"key": "abc", "nombre": "Buzo", "precio": 100.0, "sitio": "X", "url": "u"}]
    assert body["page"]["totalElements"] == 1


async def test_ver_producto_hits_the_detail_endpoint(server, backend):
    await call(server, "ver_producto", {"key": "0123456789abcdef"})

    assert sent(backend).url.path == "/api/producto/0123456789abcdef"


async def test_armar_pc_passes_preferences_through(server, backend):
    await call(server, "armar_pc", {"presupuesto": 1_000_000, "opciones": {"gama": "media", "conGpu": True}})

    req = sent(backend)
    assert req.url.path == "/api/pcs/builder"
    assert dict(req.url.params) == {"presupuesto": "1000000.0", "gama": "media", "conGpu": "true"}


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
