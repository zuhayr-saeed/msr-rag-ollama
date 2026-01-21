import sys, os, logging, logging.config, yaml
# Ensure repo root on sys.path
sys.path.append(os.path.dirname(os.path.dirname(__file__)))

from omniORB import CORBA
import CosNaming
import rag_idl
from rag_idl import _0_rag as rag

def setup_logging():
    with open(os.path.join(os.path.dirname(__file__), "..", "common", "logging.yaml")) as f:
        logging.config.dictConfig(yaml.safe_load(f))

def main():
    if len(sys.argv) < 3:
        print("Usage: python coordinator/run_one.py <PDF_PATH> <JOB_ID> [MODEL] [MAX_CHARS] [OVERLAP] [OUT_DIR]")
        sys.exit(1)

    pdf_path = sys.argv[1]
    job_id = sys.argv[2]
    model = sys.argv[3] if len(sys.argv) > 3 else "nomic-embed-text"
    max_chars = int(sys.argv[4]) if len(sys.argv) > 4 else 1200
    overlap   = int(sys.argv[5]) if len(sys.argv) > 5 else 200
    out_dir   = sys.argv[6] if len(sys.argv) > 6 else "out"

    setup_logging()
    log = logging.getLogger("coord")

    orb = CORBA.ORB_init(
        sys.argv + ["-ORBInitRef", "NameService=corbaname::127.0.0.1:2809"],
        CORBA.ORB_ID
    )
    root = orb.resolve_initial_references("NameService")._narrow(CosNaming.NamingContext)
    rag_ctx = root.resolve([CosNaming.NameComponent("RagWorker","")])._narrow(CosNaming.NamingContext)
    obj = rag_ctx.resolve([CosNaming.NameComponent("worker-001","")])
    worker = obj._narrow(rag.Worker)

    log.info(f"Dispatching job_id={job_id} pdf={pdf_path} model={model} max_chars={max_chars} overlap={overlap}")
    out_path = worker.processFile(pdf_path, job_id, model, max_chars, overlap, out_dir)
    log.info(f"Worker wrote: {out_path}")

if __name__ == "__main__":
    main()
