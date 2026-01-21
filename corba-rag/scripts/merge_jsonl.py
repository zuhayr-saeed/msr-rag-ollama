#!/usr/bin/env python3
import argparse, glob, json, os, sys
import numpy as np

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pattern", default="out/*.jsonl", help="glob of per-PDF jsonl files")
    ap.add_argument("--out_dir", default="corpus", help="where to write merged artifacts")
    ap.add_argument("--max_files", type=int, default=0, help="limit for debugging (0 = no limit)")
    args = ap.parse_args()

    files = sorted(glob.glob(args.pattern))
    if args.max_files:
        files = files[:args.max_files]
    if not files:
        print(f"No files match {args.pattern}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out_dir, exist_ok=True)
    corpus_jsonl = os.path.join(args.out_dir, "corpus.jsonl")
    meta_csv     = os.path.join(args.out_dir, "corpus_meta.csv")
    vec_npy      = os.path.join(args.out_dir, "corpus_vecs.npy")

    vecs = []
    meta_rows = []
    n_lines = 0
    model_set = set()

    with open(corpus_jsonl, "w", encoding="utf-8") as fout:
        for f in files:
            with open(f, "r", encoding="utf-8") as fin:
                for line in fin:
                    rec = json.loads(line)
                    # keep the original line in corpus.jsonl
                    fout.write(line)
                    # gather vector + metadata
                    v = rec.get("vec")
                    if v is None:
                        continue
                    vecs.append(v)
                    model_set.add(rec.get("model", ""))
                    meta_rows.append((n_lines, rec.get("job_id",""), rec.get("doc_id",""),
                                      rec.get("chunk_id", -1), rec.get("model",""),
                                      rec.get("text","").replace("\n"," ").strip()))
                    n_lines += 1

    if not vecs:
        print("No vectors found in input files.", file=sys.stderr)
        sys.exit(2)

    arr = np.asarray(vecs, dtype=np.float32)
    np.save(vec_npy, arr)

    # Write a CSV meta file (id, job_id, doc_id, chunk_id, model, text)
    # We keep text last; consumers can ignore it if large.
    with open(meta_csv, "w", encoding="utf-8") as f:
        f.write("row,job_id,doc_id,chunk_id,model,text\n")
        for row, job_id, doc_id, chunk_id, model, text in meta_rows:
            # minimal CSV escaping
            text = '"' + text.replace('"', '""') + '"'
            f.write(f"{row},{job_id},{doc_id},{chunk_id},{model},{text}\n")

    print(f"Merged {len(files)} files.")
    print(f"Wrote:\n  {corpus_jsonl}\n  {meta_csv}\n  {vec_npy}")
    if len(model_set) > 1:
        print(f"WARNING: multiple models found: {sorted(model_set)}")

if __name__ == "__main__":
    main()
