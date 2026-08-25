"""``GET /api/auth/me`` — who the caller is, read from the database every time.

The client calls this after login, after session recovery on reload, and after
**every** refresh — not only the bootstrap one — so a role change server-side
shows up inside one access-token lifetime (15 min) instead of at the next login.
That contract is only meaningful if the role really is re-read per request, and
the test below that changes a role and re-asks with the *same* token is the one
that proves it.

It is deliberately NOT on the permit list: answering "who am I" to an anonymous
caller is an oracle, so an unauthenticated call gets the chain's 401 and no
identity at all.
"""
from __future__ import annotations


def test_me_returns_the_seeded_role_for_an_admin(api, admin):
    r = api.get("/api/auth/me", token=admin.access_token)
    assert r.status == 200, f"{r.status}: {r.body!r}"

    cuerpo = r.json()
    assert cuerpo["username"] == admin.username
    assert "ADMIN" in cuerpo["roles"]
    assert isinstance(cuerpo["roles"], list), (
        "roles must be an array, never a scalar — usuario_rol is a join table "
        "that admits more than one, and collapsing it here would bake in an "
        "assumption the schema does not make"
    )


def test_me_returns_the_seeded_role_for_a_viewer(api, viewer):
    r = api.get("/api/auth/me", token=viewer.access_token)
    assert r.status == 200, f"{r.status}: {r.body!r}"

    cuerpo = r.json()
    assert cuerpo["username"] == viewer.username
    assert cuerpo["roles"] == ["VIEWER"], (
        f"a freshly created VIEWER answered roles={cuerpo['roles']}"
    )


def test_me_is_401_for_an_anonymous_caller_and_leaks_no_identity(api):
    r = api.get("/api/auth/me")
    assert r.status == 401, f"anonymous /me must be 401, got {r.status}"

    cuerpo = r.json()
    assert "username" not in cuerpo, f"an anonymous caller was told an identity: {r.body!r}"
    assert "roles" not in cuerpo, f"an anonymous caller was told a role set: {r.body!r}"
    assert cuerpo["error"] == "no_autenticado"


def test_the_role_is_not_in_the_token(api, viewer, admin):
    """A role change is visible to the SAME access token, with no re-login.

    This is the assertion, not the comment: if the role travelled inside the
    JWT, the token minted before the change would keep reporting VIEWER until
    it expired. The client is told never to decode the token to decide what to
    show, and this is why that instruction is safe to follow.
    """
    antes = api.get("/api/auth/me", token=viewer.access_token)
    assert antes.json()["roles"] == ["VIEWER"]

    promocion = api.put(
        f"/api/usuarios/{viewer.username}/rol",
        json_body={"role": "ADMIN"},
        token=admin.access_token,
    )
    assert promocion.status == 200, f"role change answered {promocion.status}: {promocion.body!r}"

    despues = api.get("/api/auth/me", token=viewer.access_token)
    assert despues.status == 200
    assert despues.json()["roles"] == ["ADMIN"], (
        "the same token still reports the old role — the role is being read "
        f"from the token instead of the database. Got {despues.json()['roles']}"
    )

    # And the authorization decision follows the database too, not just the
    # display. A /me that updates while the gate does not would be worse than
    # neither updating: the UI would offer buttons that 403.
    admin_route = api.get("/api/usuarios", token=viewer.access_token)
    assert admin_route.status == 200, (
        f"promoted to ADMIN but still refused from an ADMIN route: {admin_route.status}"
    )


def test_the_access_token_carries_no_role_claim(viewer):
    """Structural pin, so the previous test cannot be satisfied by accident.

    Decodes the JWT payload without verifying it — legitimate here precisely
    because the claim being asserted is an *absence*.
    """
    import base64
    import json

    payload_b64 = viewer.access_token.split(".")[1]
    payload_b64 += "=" * (-len(payload_b64) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload_b64))

    prohibidas = {"role", "roles", "rol", "authorities", "scope", "scopes"}
    presentes = prohibidas & set(claims)
    assert not presentes, (
        f"the access token carries {sorted(presentes)}. The whole role model "
        "assumes the token does not: it is re-read from the database on every "
        "request so a demotion takes effect immediately, and a claim here "
        "invites a client to trust a stale copy."
    )
    assert claims["sub"], "it does carry a subject, which is the part that is used"
