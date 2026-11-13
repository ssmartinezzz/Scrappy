import httpx
import pytest

from scrappy_mcp.client import ApiClient, ApiError, ForbiddenOperation


def test_logs_in_lazily_and_sends_the_bearer_token(client, backend):
    body = client.request("GET", "/api/data", params={"q": "zapatilla"})

    assert body == {"data": {"method": "GET", "path": "/api/data", "q": {"q": "zapatilla"}, "body": None}}
    assert backend.logins == 1
    assert backend.requests[-1].headers["Authorization"] == "Bearer tok1"


def test_reuses_the_token_across_requests(client, backend):
    client.request("GET", "/api/status")
    client.request("GET", "/api/facets")

    assert backend.logins == 1


def test_logs_in_again_once_when_the_token_expires(client, backend):
    client.request("GET", "/api/status")
    backend.expire_next = True

    body = client.request("GET", "/api/facets")

    assert body["data"]["path"] == "/api/facets"
    assert backend.logins == 2


def test_bad_credentials_raise_without_looping(backend):
    bad = ApiClient("http://api.test", "bot", "wrong", transport=httpx.MockTransport(backend.handler))

    with pytest.raises(ApiError) as err:
        bad.request("GET", "/api/status")

    assert err.value.status == 401
    assert len(backend.requests) == 1


def test_error_status_carries_the_body(client):
    with pytest.raises(ApiError) as err:
        client.request("POST", "/api/boom")

    assert err.value.status == 409
    assert "ocupado" in str(err.value)


def test_non_json_bodies_come_back_as_text(client):
    assert client.request("GET", "/api/csv") == "a;b\n1;2"


@pytest.mark.parametrize("method", ["DELETE", "delete", " Delete "])
def test_delete_is_refused_before_anything_is_sent(client, backend, method):
    with pytest.raises(ForbiddenOperation):
        client.request(method, "/api/favoritos", params={"url": "x"})

    assert backend.requests == []


@pytest.mark.parametrize("path", ["/api/scrape/discard", "/api/scrape/discard/", "/api//scrape/discard"])
def test_discarding_an_interrupted_run_is_refused(client, backend, path):
    with pytest.raises(ForbiddenOperation):
        client.request("POST", path)

    assert backend.requests == []


@pytest.mark.parametrize("path", ["api/data", "http://evil.test/api/data", "/actuator", "/api/../x"])
def test_only_api_paths_on_the_configured_host(client, backend, path):
    with pytest.raises(ForbiddenOperation):
        client.request("GET", path)

    assert backend.requests == []


def test_unsupported_method_is_refused(client, backend):
    with pytest.raises(ForbiddenOperation):
        client.request("TRACE", "/api/data")

    assert backend.requests == []
