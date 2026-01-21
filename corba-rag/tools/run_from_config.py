#!/usr/bin/env python3
import os, sys, subprocess, yaml, shlex

def run(cmd, env=None):
    print(f"+ {cmd}")
    r = subprocess.run(shlex.split(cmd), env=env, capture_output=True, text=True)
    if r.stdout: print(r.stdout, end="")
    if r.stderr: print(r.stderr, end="", file=sys.stderr)
    r.check_returncode()
    return r

def main():
    cfg = yaml.safe_load(open("config.yml"))
    pdf_glob = cfg["pdf_glob"]
    model    = cfg["model"]
    maxc     = str(cfg["max_chars"])
    over     = str(cfg["overlap"])
    out_dir  = cfg["out_dir"]
    workers  = cfg.get("workers", [])
    ns       = cfg.get("name_service")

    # optional: ensure omniORB knows where the NameService is
    env = os.environ.copy()
    if ns and "InitRef" not in env.get("OMNIORB_CONFIG",""):
        # if you have omniorb.cfg already, you can skip this;
        # otherwise just rely on your existing setup. No-op here.
        pass

    # quick ping of each worker
    for w in workers:
        try:
            run(f"{sys.executable} coordinator/ping.py")
        except subprocess.CalledProcessError:
            print(f"[WARN] ping failed for {w}. Make sure worker is running.", file=sys.stderr)
            raise

    # batch ingest using your existing script
    run(f"{sys.executable} coordinator/batch_ingest.py \"{pdf_glob}\" {model} {maxc} {over} {out_dir}")

if __name__ == "__main__":
    main()

