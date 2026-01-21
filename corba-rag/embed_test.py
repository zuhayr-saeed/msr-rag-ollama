import requests, sys

OLLAMA = "http://127.0.0.1:11434"
MODELS = ["nomic-embed-text", "mxbai-embed-large"]

def get_embedding(model, text):
    r = requests.post(f"{OLLAMA}/api/embeddings",
                      json={"model": model, "prompt": text},
                      timeout=60)
    r.raise_for_status()
    data = r.json()
    # On your Ollama 0.12.3, the key is "embedding" (singular)
    vec = data.get("embedding") or (data.get("embeddings")[0] if data.get("embeddings") else [])
    return vec

def main():
    for m in MODELS:
        try:
            v = get_embedding(m, "hello world")
            print(f"model={m} dim={len(v)} first5={v[:5]}")
        except Exception as e:
            print(f"model={m} ERROR: {e}", file=sys.stderr)

if __name__ == "__main__":
    main()
