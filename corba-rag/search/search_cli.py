#!/usr/bin/env python3
import argparse, csv, json, os, sys
import numpy as np
import requests
from collections import defaultdict

def load_corpus(vec_path, meta_csv):
    X = np.load(vec_path).astype(np.float32)          # [N, D]
    norms = np.linalg.norm(X, axis=1, keepdims=True) + 1e-8
    Xn = X / norms
    meta = []
    with open(meta_csv, newline="", encoding="utf-8") as f:
        r = csv.DictReader(f)
        for row in r:
            meta.append(row)
    if len(meta) != Xn.shape[0]:
        print(f"WARNING: Meta rows ({len(meta)}) != vectors ({Xn.shape[0]})", file=sys.stderr)
    return Xn, meta

def embed_query_ollama(text, model="nomic-embed-text", host="127.0.0.1", port=11434):
    url = f"http://{host}:{port}/api/embeddings"
    payload = {"model": model, "prompt": text}
    r = requests.post(url, json=payload, timeout=120)
    r.raise_for_status()
    data = r.json()
    if "embedding" in data:
        v = np.array(data["embedding"], dtype=np.float32)
    elif "embeddings" in data and data["embeddings"]:
        v = np.array(data["embeddings"][0], dtype=np.float32)
    else:
        raise RuntimeError(f"Unexpected embeddings response: {data}")
    v /= (np.linalg.norm(v) + 1e-8)
    return v

def search(Xn, meta, qvec, topk=10, filter_doc=None, dedup=True):
    scores = Xn @ qvec
    if filter_doc:
        mask = np.array([m["doc_id"] == filter_doc for m in meta], dtype=bool)
        scores = np.where(mask, scores, -np.inf)

    if not dedup:
        idx = np.argpartition(-scores, min(topk, len(scores)-1))[:topk]
        idx = idx[np.argsort(-scores[idx])]
        return [(int(i), float(scores[i])) for i in idx]

    # de-dup by (doc_id, chunk_id), keep best score
    best = {}
    for i, s in enumerate(scores):
        if not np.isfinite(s): 
            continue
        key = (meta[i]["doc_id"], meta[i]["chunk_id"])
        if key not in best or s > best[key][1]:
            best[key] = (i, float(s))
    # rank by score
    ranked = sorted(best.values(), key=lambda x: -x[1])[:topk]
    return ranked

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vecs", default="corpus/corpus_vecs.npy")
    ap.add_argument("--meta", default="corpus/corpus_meta.csv")
    ap.add_argument("--model", default="nomic-embed-text")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=11434)
    ap.add_argument("--topk", type=int, default=10)
    ap.add_argument("--doc", default=None, help="optional doc_id filter")
    ap.add_argument("--no-dedup", action="store_true", help="disable de-duplication")
    ap.add_argument("query", nargs="+", help="search query string")
    args = ap.parse_args()

    query = " ".join(args.query)
    Xn, meta = load_corpus(args.vecs, args.meta)
    qvec = embed_query_ollama(query, model=args.model, host=args.host, port=args.port)
    hits = search(Xn, meta, qvec, topk=args.topk, filter_doc=args.doc, dedup=not args.no_dedup)

    for rank, (i, score) in enumerate(hits, 1):
        m = meta[i]
        text = m["text"]
        if len(text) > 220: text = text[:220] + "..."
        print(f"{rank:2d}. score={score: .4f} | doc={m['doc_id']} | chunk={m['chunk_id']}")
        print(f"    {text}")

if __name__ == "__main__":
    main()
