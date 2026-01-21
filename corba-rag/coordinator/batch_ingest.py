#!/usr/bin/env python3
import os, sys, glob, logging, logging.config, yaml
sys.path.append(os.path.dirname(os.path.dirname(__file__)))

from omniORB import CORBA
import CosNaming
from rag_idl import _0_rag as rag

def setup_logging():
    with open(os.path.join(os.path.dirname(__file__), "..", "common", "logging.yaml")) as f:
        logging.config.dictConfig(yaml.safe_load(f))

def get_worker(orb):
    root = orb.resolve_initial_references("NameService")._narrow(CosNaming.NamingContext)
    rag_ctx = root.resolve([CosNaming.NameComponent("RagWorker","")])._narrow(CosNaming.NamingContext)
    obj = rag_ctx.resolve([CosNaming.NameComponent("worker-001","")])
    return obj._narrow(rag.Worker)

def main():
    if len(sys.argv) < 2:
        print("Usage: python coordinator/batch_ingest.py '<glob_of_pdfs>' [MODEL] [MAX_CHARS] [OVERLAP] [OUT_DIR]")
        sys.exit(1)
    pdf_glob = sys.argv[1]
    model = sys.argv[2] if len(sys.argv) > 2 else "nomic-embed-text"
    max_chars = int(sys.argv[3]) if len(sys.argv) > 3 else 1200
    overlap   = int(sys.argv[4]) if len(sys.argv) > 4 else 200
    out_dir   = sys.argv[5] if len(sys.argv) > 5 else "out"

    setup_logging()
    log = logging.getLogger("coord")

    orb = CORBA.ORB_init(
        sys.argv + ["-ORBInitRef", "NameService=corbaname::127.0.0.1:2809"],
        CORBA.ORB_ID
    )
    worker = get_worker(orb)

    pdfs = sorted(glob.glob(pdf_glob))
    if not pdfs:
        print("No PDFs matched.", file=sys.stderr)
        sys.exit(2)

    for i, pdf in enumerate(pdfs, 1):
        job_id = os.path.splitext(os.path.basename(pdf))[0]
        log.info(f"[{i}/{len(pdfs)}] {job_id} -> {pdf}")
        out_path = worker.processFile(pdf, job_id, model, max_chars, overlap, out_dir)
        log.info(f"  wrote {out_path}")

if __name__ == "__main__":
    main()
