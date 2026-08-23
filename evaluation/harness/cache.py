"""SQLite-backed classifier-response cache, keyed by a hash of everything that determines the
response deterministically (model, system prompt, input payload).

Built before the first real evaluation run, not after: the phase brief calls for re-running the
harness repeatedly while iterating on the prompt and, later, comparing a baseline run against a
deliberately degraded one for the regression-gate demonstration -- without this cache, every one
of those re-runs would re-bill the API and (since the model is not deterministic even at
temperature 0) risk reintroducing sampling variance into what's supposed to be a repeatable
comparison. A cache hit replays the exact response byte-for-byte.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS classifier_cache (
    cache_key TEXT PRIMARY KEY,
    model TEXT NOT NULL,
    system_prompt TEXT NOT NULL,
    request_payload TEXT NOT NULL,
    response_text TEXT,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS classifier_cache_stats (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    lookups INTEGER NOT NULL DEFAULT 0,
    hits INTEGER NOT NULL DEFAULT 0
);
"""


@contextmanager
def _connect(db_path: str | Path):
    conn = sqlite3.connect(str(db_path))
    try:
        yield conn
    finally:
        conn.close()


def init_cache_db(db_path: str | Path) -> None:
    with _connect(db_path) as conn:
        conn.executescript(SCHEMA)
        conn.execute("INSERT OR IGNORE INTO classifier_cache_stats (id, lookups, hits) VALUES (1, 0, 0)")
        conn.commit()


def cache_key(model: str, system_prompt: str, request_payload: str) -> str:
    """Hash of everything that determines the classifier's response deterministically. Keyed on
    the *input* (model + system prompt + the exact redacted payload sent as the user message),
    per the phase brief's "cache responses by input hash" -- a prompt change (baseline vs
    degraded, for the regression-gate demonstration) is a different key, since it is genuinely a
    different request, not a cache-invalidation edge case to work around.
    """
    payload = {"model": model, "system_prompt": system_prompt, "request_payload": request_payload}
    blob = json.dumps(payload, sort_keys=True)
    return hashlib.sha256(blob.encode()).hexdigest()


def lookup(db_path: str | Path, key: str) -> dict | None:
    init_cache_db(db_path)
    with _connect(db_path) as conn:
        conn.execute("UPDATE classifier_cache_stats SET lookups = lookups + 1 WHERE id = 1")
        row = conn.execute(
            "SELECT response_text, input_tokens, output_tokens FROM classifier_cache WHERE cache_key = ?",
            (key,),
        ).fetchone()
        if row is None:
            conn.commit()
            return None
        conn.execute("UPDATE classifier_cache_stats SET hits = hits + 1 WHERE id = 1")
        conn.commit()
        return {"response_text": row[0], "input_tokens": row[1], "output_tokens": row[2]}


def store(
    db_path: str | Path,
    key: str,
    model: str,
    system_prompt: str,
    request_payload: str,
    response_text: str | None,
    input_tokens: int,
    output_tokens: int,
) -> None:
    init_cache_db(db_path)
    with _connect(db_path) as conn:
        conn.execute(
            "INSERT OR REPLACE INTO classifier_cache "
            "(cache_key, model, system_prompt, request_payload, response_text, input_tokens, output_tokens) "
            "VALUES (?,?,?,?,?,?,?)",
            (key, model, system_prompt, request_payload, response_text, input_tokens, output_tokens),
        )
        conn.commit()


def stats(db_path: str | Path) -> dict:
    init_cache_db(db_path)
    with _connect(db_path) as conn:
        entries = conn.execute("SELECT COUNT(*) FROM classifier_cache").fetchone()[0]
        lookups, hits = conn.execute("SELECT lookups, hits FROM classifier_cache_stats WHERE id = 1").fetchone()
    return {
        "entries": entries,
        "lookups": lookups,
        "hits": hits,
        "hit_rate": (hits / lookups) if lookups else 0.0,
    }
