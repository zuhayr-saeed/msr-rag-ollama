#!/usr/bin/env python3
import os, re, sys, json, math, csv, argparse, yaml
from collections import Counter, defaultdict
from typing import Dict, List, Tuple
import numpy as np

# ---- tokenization (simple, consistent) ----
TOKEN_RE = re.compile(r"[A-Za-z0-9_]+(?:'[A-Za-z0-9_]+)?")

def tokenize(text: str) -> List[str]:
    return [t.lower() for t in TOKEN_RE.findall(text)]

# ---- cosine similarity ----
def cosine(a: np.ndarray, b: np.ndarray) -> float:
    na = np.linalg.norm(a)
    nb = np.linalg.norm(b)
    if na == 0.0 or nb == 0.0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))

def parse_args():
    p = argparse.ArgumentParser(description="Build vocab and nearest neighbors from RAG JSONL embeddings.")
    p.add_argument("--inputs", nargs="+", default=["out/*.jsonl"], help="Glob(s) or paths to JSONL files.")
    p.add_argument("--outdir", default="analytics", help="Output directory.")
    p.add_argument("--min_df", type=int, default=3, help="Min documents/chunks a token must appear in.")
    p.add_argument("--max_tokens", type=int, default=5000, help="Cap vocab to top-N tokens by DF (to keep NN fast).")
    p.add_argument("--topk", type=int, default=10, help="Top-K neighbors per token.")
    p.add_argument("--dump_yaml", action="store_true", help="Also write vocab.yaml")
    return p.parse_args()

def expand_inputs(globs_or_paths: List[str]) -> List[str]:
    import glob
    files = []
    for pat in globs_or_paths:
        files.extend(glob.glob(pat))
    # unique, stable order
    return sorted(set(files))

def main():
    args = parse_args()
    os.makedirs(args.outdir, exist_ok=True)
    inputs = expand_inputs(args.inputs)
    if not inputs:
        print("No input JSONL files found.", file=sys.stderr)
        sys.exit(1)

    # 1) pass 1: gather DF (per-chunk presence) and TF (total occurrences)
    df = Counter()               # in how many chunks token appears
    tf = Counter()               # total token count across chunks
    chunk_texts: List[Tuple[str,int,str]] = []  # (doc_id, chunk_id, text)
    embed_dim = None

    # Also keep a small list of (chunk_idx -> vector) so we can add into token sums later
    chunk_vecs: List[np.ndarray] = []

    for fpath in inputs:
        with open(fpath, "r", encoding="utf-8") as f:
            for line in f:
                rec = json.loads(line)
                text = rec["text"]
                tokens = tokenize(text)

                # Update TF
                tf.update(tokens)
                # Update DF (unique within chunk)
                df.update(set(tokens))

                if embed_dim is None:
                    embed_dim = len(rec.get("vec") or [])
                v = np.array(rec.get("vec") or [], dtype=np.float32)
                if embed_dim and v.size != embed_dim:
                    # if mismatch, skip this line
                    continue

                chunk_idx = len(chunk_texts)
                chunk_texts.append((rec.get("doc_id",""), int(rec.get("chunk_id",chunk_idx)), text))
                chunk_vecs.append(v)

    if embed_dim is None or embed_dim == 0:
        print("No embeddings found in inputs.", file=sys.stderr)
        sys.exit(2)

    # 2) filter vocab by min_df and cap to max_tokens
    #    We use DF to choose salient tokens (chunk coverage)
    vocab_tokens = [t for t,c in df.items() if c >= args.min_df]
    # sort by DF desc then TF desc then token asc for stability
    vocab_tokens.sort(key=lambda t: (-df[t], -tf[t], t))
    if len(vocab_tokens) > args.max_tokens:
        vocab_tokens = vocab_tokens[:args.max_tokens]

    token2id: Dict[str,int] = {t:i for i,t in enumerate(vocab_tokens)}
    V = len(token2id)
    print(f"Using vocab size={V} (min_df={args.min_df}, cap={args.max_tokens}), dim={embed_dim}, from {len(chunk_texts)} chunks")

    # 3) pass 2: build token vectors by averaging chunk embeddings where token appears
    sums = np.zeros((V, embed_dim), dtype=np.float32)
    counts = np.zeros((V,), dtype=np.int32)

    for (doc_id, chunk_id, text), v in zip(chunk_texts, chunk_vecs):
        toks = set(tokenize(text))  # presence
        for t in toks:
            idx = token2id.get(t)
            if idx is not None:
                sums[idx] += v
                counts[idx] += 1

    # avoid divide by zero
    counts[counts == 0] = 1
    token_vecs = sums / counts.reshape(-1,1)

    # 4) write vocab.csv (id,token,df,tf)
    vocab_csv = os.path.join(args.outdir, "vocab.csv")
    with open(vocab_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id","token","df","tf"])
        for t, i in token2id.items():
            w.writerow([i, t, df[t], tf[t]])

    # optional YAML dump (id->token plus stats)
    if args.dump_yaml:
        vocab_yaml = os.path.join(args.outdir, "vocab.yaml")
        with open(vocab_yaml, "w", encoding="utf-8") as f:
            y = [{"id": token2id[t], "token": t, "df": int(df[t]), "tf": int(tf[t])} for t in token2id]
            yaml.safe_dump(y, f, sort_keys=False)

    # 5) save token vectors (numpy)
    vecs_npy = os.path.join(args.outdir, "token_vecs.npy")
    np.save(vecs_npy, token_vecs)

    # 6) nearest neighbors (brute force; manageable for <= 5k tokens)
    #    Normalize for cosine; then compute topK via matrix multiply
    #    To limit memory, do it in blocks if needed.
    # Normalize
    norms = np.linalg.norm(token_vecs, axis=1, keepdims=True)
    norms[norms == 0.0] = 1.0
    Z = token_vecs / norms

    # block compute similarities
    K = args.topk
    nn_csv = os.path.join(args.outdir, "nn.csv")
    with open(nn_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["token","neighbor","cosine"])
        block = 512
        for start in range(0, V, block):
            end = min(start + block, V)
            S = Z[start:end] @ Z.T  # (B, V)
            # remove self by setting diag to -inf
            for i in range(start, end):
                S[i-start, i] = -np.inf
            # top-K
            idxs = np.argpartition(-S, kth=min(K, V-1)-1, axis=1)[:, :K]
            # sort those K
            for row, inds in enumerate(idxs):
                sims = S[row, inds]
                order = np.argsort(-sims)
                tok_i = start + row
                tok = vocab_tokens[tok_i]
                for j in order:
                    nb_id = int(inds[j])
                    w.writerow([tok, vocab_tokens[nb_id], float(S[row, nb_id])])

    print(f"Wrote:\n  {vocab_csv}\n  {vecs_npy}\n  {nn_csv}")
    if args.dump_yaml:
        print(f"  {os.path.join(args.outdir, 'vocab.yaml')}")

if __name__ == "__main__":
    main()
