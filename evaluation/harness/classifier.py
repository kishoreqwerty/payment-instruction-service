"""Calls the real classifier model and parses its response -- the Python-side mirror of
exception-service's `ClassifierClient` (see that class's javadoc), so the harness measures
exactly what is deployed rather than a reimplementation that might drift from it. Model,
max_tokens, system prompt text, and the defensive-parsing/markdown-fence-stripping behavior are
all copied from there deliberately; if that Java class's prompt or parsing ever changes, this
module has to change with it or the numbers in the phase report stop meaning anything.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

from harness import cache

MAX_TOKENS = 500

VALID_REPAIRABILITY = {"REPAIRABLE", "STATIC_DATA", "TRANSIENT", "UNREPAIRABLE"}


@dataclass
class Proposal:
    reason_code: str | None
    repairability: str | None
    suggested_field: str | None
    suggested_value: str | None
    confidence: float
    rationale: str | None


def load_system_prompt(path: str | Path) -> str:
    return Path(path).read_text()


def _strip_markdown_fence(text: str) -> str:
    trimmed = text.strip()
    if trimmed.startswith("```"):
        first_newline = trimmed.find("\n")
        last_fence = trimmed.rfind("```")
        if first_newline != -1 and last_fence > first_newline:
            return trimmed[first_newline + 1 : last_fence].strip()
    return trimmed


def parse_proposal(text: str | None) -> Proposal | None:
    """A malformed response is `None`, never an exception -- same contract as
    `ClassifierClient#parseProposal` on the Java side."""
    if not text or not text.strip():
        return None
    try:
        node = json.loads(_strip_markdown_fence(text))
    except json.JSONDecodeError:
        return None
    repairability = node.get("repairability")
    if repairability not in VALID_REPAIRABILITY:
        return None
    return Proposal(
        reason_code=node.get("reasonCode"),
        repairability=repairability,
        suggested_field=node.get("suggestedField"),
        suggested_value=node.get("suggestedValue"),
        confidence=float(node.get("confidence", 0.0)),
        rationale=node.get("rationale"),
    )


class ClassifierRunner:
    """Thin wrapper over the Anthropic SDK with the cache in front of it. Constructed once per
    evaluation run (one model, one system prompt); `classify` is called once per labelled case.
    """

    def __init__(self, model: str, system_prompt: str, cache_db_path: str | Path, api_key: str | None = None):
        self.model = model
        self.system_prompt = system_prompt
        self.cache_db_path = cache_db_path
        self._api_key = api_key or os.environ.get("ANTHROPIC_API_KEY", "")
        self._client = None

    def _client_or_raise(self):
        if not self._api_key:
            raise RuntimeError("ANTHROPIC_API_KEY not set: cannot make live classifier calls")
        if self._client is None:
            import anthropic

            self._client = anthropic.Anthropic(api_key=self._api_key)
        return self._client

    def classify(self, request_payload: dict) -> tuple[Proposal | None, bool]:
        """Returns (proposal, was_cache_hit). `request_payload` is the exact
        `ClassifierRequest` JSON object already recorded in the labelled dataset (built by the
        real `PromptRedactor` during generation) -- sent as the user message verbatim, the same
        as `ClassifierClient#classify` does with `json.writeValueAsString(request)`.
        """
        payload_text = json.dumps(request_payload, sort_keys=True)
        key = cache.cache_key(self.model, self.system_prompt, payload_text)

        cached = cache.lookup(self.cache_db_path, key)
        if cached is not None:
            return parse_proposal(cached["response_text"]), True

        client = self._client_or_raise()
        response = client.messages.create(
            model=self.model,
            max_tokens=MAX_TOKENS,
            system=self.system_prompt,
            messages=[{"role": "user", "content": payload_text}],
        )
        text = None
        for block in response.content:
            if getattr(block, "type", None) == "text":
                text = block.text
                break
        cache.store(
            self.cache_db_path,
            key,
            self.model,
            self.system_prompt,
            payload_text,
            text,
            response.usage.input_tokens,
            response.usage.output_tokens,
        )
        return parse_proposal(text), False
