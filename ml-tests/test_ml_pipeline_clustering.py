# -*- coding: utf-8 -*-
"""
Characterization + equivalence tests for ``ml_pipeline.cluster_productos``.

The greedy TF-IDF clustering feeds the ``trending`` badge and
``trendingClusters``: every product is assigned to the first centroid whose
cosine similarity beats the threshold, or seeds a new cluster of its own.
It had no test coverage, which made it impossible to optimize safely — and it
badly needed optimizing (it was quadratic: ~11.4s for a 6700-product catalog).

Two layers of protection here:

1. **Characterization tests** pin the observable contract — id numbering,
   tokenizer behaviour, threshold strictness, centroid drift.
2. **``_cluster_referencia``** is a verbatim copy of the original
   implementation, kept as an oracle. The equivalence tests assert the shipped
   function returns *exactly* the same id list on randomized corpora. An
   optimization that changes a single assignment fails here.

Note for anyone extending these: the tokenizer regex is
``[a-zA-ZáéíóúñÁÉÍÓÚÑ]+`` — it matches letters only. A synthetic vocabulary
like ``tok1, tok2, …`` therefore collapses to the single token ``tok``, every
document becomes identical, and the whole corpus lands in one cluster. Build
test names from *alphabetic* tokens or the corpus proves nothing.
"""
import math
import random
import string
from collections import defaultdict

import ml_pipeline


# ─── Oracle: the original implementation, kept verbatim ──────────────────────


def _cosine_sim_referencia(a, b):
    keys = set(a) & set(b)
    if not keys:
        return 0.0
    dot = sum(a[k] * b[k] for k in keys)
    na = math.sqrt(sum(v * v for v in a.values()))
    nb = math.sqrt(sum(v * v for v in b.values()))
    return dot / (na * nb) if na and nb else 0.0


def _cluster_referencia(nombres, threshold=0.28):
    """The pre-optimization implementation, unchanged. Slow on purpose."""
    if not nombres:
        return []
    vecs = ml_pipeline.tfidf_simple(nombres)
    n = len(vecs)
    cluster_id = [-1] * n
    next_cluster = 0
    centroids = []

    for i in range(n):
        best_cluster, best_sim = -1, threshold
        for ci, centroid in enumerate(centroids):
            sim = _cosine_sim_referencia(vecs[i], centroid)
            if sim > best_sim:
                best_sim, best_cluster = sim, ci

        if best_cluster == -1:
            cluster_id[i] = next_cluster
            centroids.append(dict(vecs[i]))
            next_cluster += 1
        else:
            cluster_id[i] = best_cluster
            c = centroids[best_cluster]
            members = sum(1 for cid in cluster_id if cid == best_cluster)
            for k in set(c) | set(vecs[i]):
                c[k] = (c.get(k, 0) * (members - 1) + vecs[i].get(k, 0)) / members

    return cluster_id


# ─── Corpus helpers ──────────────────────────────────────────────────────────


def _palabra(rng, largo=6):
    """A pronounceable-enough alphabetic token the tokenizer will actually keep."""
    return "".join(rng.choice(string.ascii_lowercase) for _ in range(largo))


def _corpus_disperso(rng, n, vocabulario, por_nombre=6):
    """High-entropy names: many small clusters (the expensive shape)."""
    return [" ".join(rng.sample(vocabulario, por_nombre)) for _ in range(n)]


def _corpus_agrupado(rng, n, familias=12, por_nombre=6):
    """Names drawn from a few overlapping families: few, large clusters."""
    vocab_familia = [[_palabra(rng) for _ in range(8)] for _ in range(familias)]
    ruido = [_palabra(rng) for _ in range(200)]
    nombres = []
    for _ in range(n):
        fam = rng.choice(vocab_familia)
        tokens = rng.sample(fam, min(por_nombre - 1, len(fam))) + [rng.choice(ruido)]
        rng.shuffle(tokens)
        nombres.append(" ".join(tokens))
    return nombres


# ─── Characterization: the observable contract ───────────────────────────────


def test_lista_vacia_devuelve_lista_vacia():
    assert ml_pipeline.cluster_productos([]) == []


def test_un_solo_producto_es_el_cluster_cero():
    assert ml_pipeline.cluster_productos(["campera inflable pluma andina"]) == [0]


def test_devuelve_un_id_por_producto():
    nombres = ["alfa beta gamma", "delta epsilon zeta", "alfa beta gamma"]
    assert len(ml_pipeline.cluster_productos(nombres)) == len(nombres)


def test_nombres_identicos_caen_en_el_mismo_cluster():
    nombres = ["montania trekking impermeable", "montania trekking impermeable"]
    ids = ml_pipeline.cluster_productos(nombres)
    assert ids[0] == ids[1]


def test_nombres_sin_tokens_en_comun_no_se_agrupan():
    """Cosine similarity of disjoint vectors is 0.0, which never beats the
    threshold — so each name has to seed its own cluster."""
    ids = ml_pipeline.cluster_productos(
        ["montania trekking impermeable", "quilmes birrete escarpines"]
    )
    assert ids[0] != ids[1]


def test_los_ids_se_asignan_en_orden_de_aparicion_desde_cero():
    """First product is always cluster 0, and each new cluster takes the next
    integer — downstream code counts sizes off these ids."""
    ids = ml_pipeline.cluster_productos(
        [
            "montania trekking impermeable",
            "quilmes birrete escarpines",
            "montania trekking impermeable",
            "vinilo cassette walkman",
        ]
    )
    assert ids == [0, 1, 0, 2]


def test_el_tokenizador_descarta_digitos():
    """The regex matches letters only: two names differing solely in their
    numeric part tokenize identically and cannot be told apart."""
    ids = ml_pipeline.cluster_productos(
        ["montania trekking modelo 1234", "montania trekking modelo 9999"]
    )
    assert ids[0] == ids[1]


def test_el_tokenizador_descarta_tokens_de_hasta_dos_letras():
    """`len(w) > 2` — so a name made only of short tokens has an empty vector,
    and an empty vector matches nothing (not even another empty one)."""
    ids = ml_pipeline.cluster_productos(["xs m l", "xs m l"])
    assert ids[0] != ids[1]


def test_las_stopwords_no_aportan_similitud():
    """`remera` and `campera` are stopwords: two names sharing only those
    stay in separate clusters."""
    ids = ml_pipeline.cluster_productos(
        ["remera campera montania trekking", "remera campera quilmes birrete"]
    )
    assert ids[0] != ids[1]


def test_el_umbral_es_estrictamente_mayor():
    """`sim > best_sim` starting at `threshold`: a similarity exactly equal to
    the threshold does NOT join the cluster. Pinned with threshold=0.0, where
    disjoint vectors score exactly 0.0."""
    ids = ml_pipeline.cluster_productos(
        ["montania trekking impermeable", "quilmes birrete escarpines"], threshold=0.0
    )
    assert ids[0] != ids[1]


def test_umbral_alto_deja_cada_producto_en_su_cluster():
    nombres = ["montania trekking impermeable", "montania trekking impermeable"]
    assert ml_pipeline.cluster_productos(nombres, threshold=1.5) == [0, 1]


def test_el_centroide_se_corre_hacia_los_miembros_que_absorbe():
    """Centroid is a running mean, so a product joining a cluster drags it
    toward its own vocabulary — a later product sharing only the newcomer's
    tokens can then match a cluster its seed had nothing in common with."""
    nombres = [
        "montania trekking impermeable",
        "montania trekking impermeable capucha polar abrigo",
        "capucha polar abrigo",
    ]
    ids = ml_pipeline.cluster_productos(nombres, threshold=0.2)
    assert ids[0] == ids[1] == ids[2]


# ─── Equivalence against the oracle ──────────────────────────────────────────


def test_equivalencia_en_corpus_disperso():
    """Many tiny clusters — the shape that made the original quadratic."""
    rng = random.Random(11)
    vocabulario = [_palabra(rng) for _ in range(4000)]
    nombres = _corpus_disperso(rng, 900, vocabulario)
    assert ml_pipeline.cluster_productos(nombres) == _cluster_referencia(nombres)


def test_equivalencia_en_corpus_agrupado():
    """Few large clusters — exercises repeated centroid updates and the
    member-count arithmetic that drives the running mean."""
    rng = random.Random(23)
    nombres = _corpus_agrupado(rng, 900)
    assert ml_pipeline.cluster_productos(nombres) == _cluster_referencia(nombres)


def test_equivalencia_con_duplicados_masivos():
    """A catalog where the same product is listed by many sites: the merge
    path runs far more often than the seed path."""
    rng = random.Random(37)
    base = [" ".join(_palabra(rng) for _ in range(5)) for _ in range(40)]
    nombres = [rng.choice(base) for _ in range(600)]
    rng.shuffle(nombres)
    assert ml_pipeline.cluster_productos(nombres) == _cluster_referencia(nombres)


def test_equivalencia_con_nombres_vacios_y_solo_stopwords():
    """Empty TF-IDF vectors (blank names, stopword-only names, short-token-only
    names) must be assigned exactly as the original did — they match nothing,
    so each takes a fresh id."""
    nombres = [
        "",
        "   ",
        "de la el los",
        "xs m l",
        "montania trekking impermeable",
        "",
        "montania trekking impermeable",
    ]
    assert ml_pipeline.cluster_productos(nombres) == _cluster_referencia(nombres)


def test_equivalencia_en_varios_umbrales():
    """The threshold is a parameter; equivalence has to hold across its range,
    not just at the 0.28 default."""
    rng = random.Random(53)
    nombres = _corpus_agrupado(rng, 400, familias=6)
    for threshold in (0.05, 0.15, 0.28, 0.45, 0.80):
        assert ml_pipeline.cluster_productos(nombres, threshold) == _cluster_referencia(
            nombres, threshold
        ), f"divergencia con threshold={threshold}"


def test_equivalencia_estable_ante_semillas_distintas():
    """Ten independent random corpora, so the guarantee is not an artifact of
    one lucky seed."""
    for semilla in range(10):
        rng = random.Random(semilla)
        vocabulario = [_palabra(rng) for _ in range(300)]
        nombres = _corpus_disperso(rng, 200, vocabulario, por_nombre=5)
        assert ml_pipeline.cluster_productos(nombres) == _cluster_referencia(
            nombres
        ), f"divergencia con semilla={semilla}"
