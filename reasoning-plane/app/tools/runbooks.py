"""Runbook RAG over a small committed corpus.

Uses a dependency-free TF-IDF cosine ranker so it runs offline in tests and the mock
provider. In production this is backed by pgvector (compose provisions it); swapping the
ranker for embedding search is a localised change. See ADR notes / TODO(human).
"""

from __future__ import annotations

import math
import re
from collections import Counter
from pathlib import Path

_WORD = re.compile(r"[a-z0-9]+")


def _tokenize(text: str) -> list[str]:
    return _WORD.findall(text.lower())


class RunbookIndex:
    """In-memory TF-IDF index over committed runbook markdown files."""

    def __init__(self, docs: dict[str, str]) -> None:
        self._titles = list(docs.keys())
        self._texts = list(docs.values())
        self._doc_tokens = [_tokenize(t) for t in self._texts]
        self._df: Counter[str] = Counter()
        for tokens in self._doc_tokens:
            for term in set(tokens):
                self._df[term] += 1
        self._n = max(1, len(self._texts))

    def _idf(self, term: str) -> float:
        return math.log((self._n + 1) / (self._df.get(term, 0) + 1)) + 1.0

    def _vector(self, tokens: list[str]) -> dict[str, float]:
        tf = Counter(tokens)
        return {term: (count / len(tokens)) * self._idf(term) for term, count in tf.items()}

    @staticmethod
    def _cosine(a: dict[str, float], b: dict[str, float]) -> float:
        common = set(a) & set(b)
        num = sum(a[t] * b[t] for t in common)
        na = math.sqrt(sum(v * v for v in a.values()))
        nb = math.sqrt(sum(v * v for v in b.values()))
        return num / (na * nb) if na and nb else 0.0

    def search(self, query: str, k: int = 2) -> list[tuple[str, str]]:
        q_tokens = _tokenize(query)
        if not q_tokens:
            return []
        qv = self._vector(q_tokens)
        docs = zip(self._titles, self._texts, self._doc_tokens, strict=True)
        scored = [
            (self._cosine(qv, self._vector(tokens)), title, text) for title, text, tokens in docs
        ]
        scored.sort(key=lambda x: x[0], reverse=True)
        results: list[tuple[str, str]] = []
        for score, title, text in scored[:k]:
            if score <= 0:
                continue
            snippet = " ".join(text.split())[:240]
            results.append((title, snippet))
        return results

    @classmethod
    def from_dir(cls, directory: str | Path) -> RunbookIndex:
        path = Path(directory)
        docs: dict[str, str] = {}
        if path.is_dir():
            for md in sorted(path.glob("*.md")):
                if md.name.startswith("._"):  # skip macOS AppleDouble sidecars (exFAT)
                    continue
                docs[md.stem] = md.read_text(encoding="utf-8")
        if not docs:
            docs["placeholder"] = "no runbooks available"
        return cls(docs)
