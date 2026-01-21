#!/usr/bin/env python3
import os, csv, re
from typing import Optional, List, Dict, Any
import numpy as np
import requests
from fastapi import FastAPI, Query, HTTPException
from pydantic import BaseModel
from dotenv import load_dotenv
load_dotenv()

# -------- Config (env overridable) --------
VEC_PATH = os.environ.get("RAG_VECS", "corpus/corpus_vecs.npy")
META_CSV = os.environ.get("RAG_META", "corpus/corpus_meta.csv")
EMBED_MODEL = os.environ.get("RAG_EMBED_MODEL", "nomic-embed-text")
OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "127.0.0.1")
OLLAMA_PORT = int(os.environ.get("OLLAMA_PORT", "11434"))
GEN_MODEL   = os.environ.get("RAG_GEN_MODEL", "llama3.2:3b")  # default chat model

# -------- Load corpus once --------
X = np.load(VEC_PATH).astype(np.float32)
X /= (np.linalg.norm(X, axis=1, keepdims=True) + 1e-8)

META: List[Dict[str, Any]] = []
with open(META_CSV, newline="", encoding="utf-8") as f:
    r = csv.DictReader(f)
    META.extend(r)

def _ollama_url(path: str) -> str:
    return f"http://{OLLAMA_HOST}:{OLLAMA_PORT}{path}"

# -------- Embedding via Ollama --------
def embed_query_ollama(text: str) -> np.ndarray:
    url = _ollama_url("/api/embeddings")
    payload = {"model": EMBED_MODEL, "prompt": text}
    resp = requests.post(url, json=payload, timeout=120)
    try:
        resp.raise_for_status()
    except requests.HTTPError as e:
        detail = f"Ollama embeddings error ({resp.status_code}): {resp.text}"
        raise HTTPException(status_code=502, detail=detail) from e
    data = resp.json()
    if "embedding" in data:
        v = np.array(data["embedding"], dtype=np.float32)
    elif "embeddings" in data and data["embeddings"]:
        v = np.array(data["embeddings"][0], dtype=np.float32)
    else:
        raise HTTPException(status_code=502, detail=f"Unexpected embeddings response: {data}")
    v /= (np.linalg.norm(v) + 1e-8)
    return v

# -------- Vector search --------
def search(qvec: np.ndarray, topk: int = 10, doc: Optional[str] = None):
    scores = X @ qvec
    if doc:
        mask = np.array([m["doc_id"] == doc for m in META], dtype=bool)
        scores = np.where(mask, scores, -np.inf)

    # de-dup by (doc_id, chunk_id)
    best = {}
    for i, s in enumerate(scores):
        if not np.isfinite(s):
            continue
        key = (META[i]["doc_id"], META[i]["chunk_id"])
        if key not in best or s > best[key][1]:
            best[key] = (i, float(s))
    ranked = sorted(best.values(), key=lambda x: -x[1])[:topk]

    out = []
    for i, s in ranked:
        m = META[i]
        out.append({
            "row": i,
            "score": s,
            "doc_id": m["doc_id"],
            "chunk_id": int(m["chunk_id"]),
            "text": m["text"],
        })
    return out

# -------- LLM generation (with graceful errors) --------
def ollama_generate(prompt: str, model: Optional[str] = None) -> str:
    m = model or GEN_MODEL
    gen_url = _ollama_url("/api/generate")
    payload = {"model": m, "prompt": prompt, "stream": False}
    r = requests.post(gen_url, json=payload, timeout=600)

    if r.status_code == 404:
        try:
            msg = r.json().get("error", "")
        except Exception:
            msg = r.text
        raise HTTPException(
            status_code=502,
            detail=f"Ollama model '{m}' not found for /api/generate. "
                   f"Run: `ollama pull {m}` or pass ?gmodel=installed-model. Server: {msg}"
        )
    if r.status_code >= 400:
        # Try chat fallback
        chat_url = _ollama_url("/api/chat")
        chat_payload = {"model": m, "messages": [{"role": "user", "content": prompt}], "stream": False}
        r2 = requests.post(chat_url, json=chat_payload, timeout=600)
        try:
            r2.raise_for_status()
        except requests.HTTPError as e:
            raise HTTPException(
                status_code=502,
                detail=f"Ollama chat fallback failed ({r2.status_code}). "
                       f"generate_resp={r.status_code}:{r.text} chat_resp={r2.status_code}:{r2.text}"
            ) from e
        data = r2.json()
        return (data.get("message") or {}).get("content", "").strip()

    r.raise_for_status()
    return r.json().get("response", "").strip()

# -------- Prompt builder --------
def build_prompt(question: str, results: List[dict], max_ctx_chars: int = 4000, answer_style: str = "default") -> str:
    style_hint = ""
    if answer_style == "percent":
        style_hint = "Return ONLY the percentage (e.g., 12% or 12.2%).\n"
    elif answer_style == "short":
        style_hint = "Answer concisely in one short sentence.\n"

    header = (
        "Answer using ONLY the provided context. If it isn't in the context, say you don't know.\n"
        + style_hint +
        f"\nQuestion: {question}\n\nContext:\n"
    )
    ctx, used = [], 0
    for r in results:
        chunk = f"[doc={r['doc_id']} chunk={r['chunk_id']} score={r['score']:.3f}]\n{r['text']}\n\n"
        if used + len(chunk) > max_ctx_chars:
            break
        ctx.append(chunk); used += len(chunk)
    return header + "".join(ctx)

# -------- Simple extractive % finder --------
_PERC_PATTERNS = [
    # e.g., "about 12% of the 293 questions were covered."
    re.compile(r"(?i)\b(?:about|approximately|around)?\s*(\d{1,3}(?:\.\d+)?)\s*%\s+of\s+the\s+\d+\s+(?:related\s+)?questions\s+were\s+covered"),
    # e.g., "... 12% ... covered" within ~80 chars
    re.compile(r"(?i)\b(\d{1,3}(?:\.\d+)?)\s*%[^%.]{0,80}\bcovered"),
    # generic "coverage ... 12.2%" near the word coverage
    re.compile(r"(?i)coverage[^%]{0,80}?(\d{1,3}(?:\.\d+)?)\s*%"),
]

def extract_percent_from_context(hits: List[dict]) -> Optional[Dict[str, str]]:
    candidates = []
    for h in hits:
        t = h["text"]
        for pat in _PERC_PATTERNS:
            for m in pat.finditer(t):
                val = m.group(1)
                # precision score: prefer decimals, then larger value (rare tie-break)
                frac = 1 if "." in val else 0
                candidates.append({
                    "val": val,
                    "frac": frac,
                    "float": float(val),
                    "doc_id": h["doc_id"],
                    "chunk_id": str(h["chunk_id"]),
                    "snippet": t[max(0, m.start()-80): m.end()+80],
                })
    if not candidates:
        return None
    # sort: decimals first, then by value (desc), then keep earliest candidate
    candidates.sort(key=lambda c: (c["frac"], c["float"]), reverse=True)
    best = candidates[0]
    return {
        "answer": f'{best["val"]}%',
        "doc_id": best["doc_id"],
        "chunk_id": best["chunk_id"],
        "snippet": best["snippet"],
    }


# -------- API models --------
class AskResponse(BaseModel):
    answer: str
    hits: List[dict]
    evidence: Optional[dict] = None

# -------- FastAPI --------
app = FastAPI()

@app.get("/health")
def health():
    return {
        "status": "ok",
        "vecs": VEC_PATH,
        "meta": META_CSV,
        "n_rows": len(META),
        "embed_model": EMBED_MODEL,
        "gen_model_default": GEN_MODEL,
    }

@app.get("/search")
def api_search(q: str = Query(..., description="query"),
               topk: int = 10,
               doc: Optional[str] = None):
    qvec = embed_query_ollama(q)
    return {"query": q, "topk": topk, "doc": doc, "results": search(qvec, topk=topk, doc=doc)}

@app.get("/ask", response_model=AskResponse)
def api_ask(q: str = Query(..., description="question"),
            topk: int = 8,
            doc: Optional[str] = None,
            gmodel: Optional[str] = Query(None, description="override generation model, e.g., llama3.2:3b"),
            format: Optional[str] = Query(None, description="use 'percent' to extract a percentage deterministically"),
            style: Optional[str]  = Query(None, description="generation hint: 'short' or 'percent'")):
    qvec = embed_query_ollama(q)
    results = search(qvec, topk=topk, doc=doc)

    # Extractive path for numeric % answers
    if format == "percent":
        found = extract_percent_from_context(results)
        if found:
            return AskResponse(answer=found["answer"], hits=results, evidence=found)
        # fall through to LLM if nothing matched

    prompt = build_prompt(q, results, answer_style=(style or "default"))
    answer = ollama_generate(prompt, model=gmodel)
    return AskResponse(answer=answer, hits=results)
