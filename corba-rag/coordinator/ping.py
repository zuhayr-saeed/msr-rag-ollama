import sys, os, logging, logging.config, yaml
# Ensure repo root on sys.path to import rag_idl from parent dir
sys.path.append(os.path.dirname(os.path.dirname(__file__)))

from omniORB import CORBA
import CosNaming

# Generated stubs
import rag_idl
# The interface types live under _0_rag (because your IDL has: module rag { ... })
from rag_idl import _0_rag as rag

def setup_logging():
    with open(os.path.join(os.path.dirname(__file__), "..", "common", "logging.yaml")) as f:
        logging.config.dictConfig(yaml.safe_load(f))

def main():
    setup_logging()
    log = logging.getLogger("coord")

    # Pass NameService endpoint explicitly
    orb = CORBA.ORB_init(
        sys.argv + ["-ORBInitRef", "NameService=corbaname::127.0.0.1:2809"],
        CORBA.ORB_ID
    )
    naming = orb.resolve_initial_references("NameService")
    root = naming._narrow(CosNaming.NamingContext)

    rag_ctx = root.resolve([CosNaming.NameComponent("RagWorker","")])._narrow(CosNaming.NamingContext)
    obj = rag_ctx.resolve([CosNaming.NameComponent("worker-001","")])
    worker = obj._narrow(rag.Worker)

    log.info("Calling ping() on worker-001 ...")
    worker.ping()
    log.info("Success! The worker responded.")

if __name__ == "__main__":
    main()
