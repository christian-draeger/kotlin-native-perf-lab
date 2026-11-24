#!/usr/bin/env python3
"""Verify the CPU reference for the ktServe inference experiment."""

import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import tempfile
from urllib.request import urlopen

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


MODEL = "sentence-transformers/all-MiniLM-L6-v2"
REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
MODEL_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"
TOKENIZER_SHA256 = "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"
SENTENCES = [
    "The HTTP client reuses its existing connection.",
    "A persistent HTTP connection lets the client reuse its socket.",
    "The bakery prepares sourdough bread before sunrise.",
    "Die Bibliothek verarbeitet eine Antwort mit UTF-8.",
]


def model_file(directory, name, expected_sha256):
    def verify(path):
        with path.open("rb") as candidate:
            actual_sha256 = hashlib.file_digest(candidate, "sha256").hexdigest()
        if actual_sha256 != expected_sha256:
            raise ValueError(f"SHA-256 mismatch for {path}")

    destination = directory / Path(name).name
    if destination.exists():
        verify(destination)
        return destination
    url = f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}"
    with tempfile.TemporaryDirectory(dir=directory) as scratch:
        candidate = Path(scratch) / destination.name
        with urlopen(url, timeout=60) as source, candidate.open("wb") as output:
            shutil.copyfileobj(source, output)
        verify(candidate)
        candidate.replace(destination)
    return destination


def embed(session, tokenizer, sentences):
    encoded = tokenizer.encode_batch(sentences)
    inputs = {
        "input_ids": np.asarray([sentence.ids for sentence in encoded], dtype=np.int64),
        "attention_mask": np.asarray([sentence.attention_mask for sentence in encoded], dtype=np.int64),
        "token_type_ids": np.asarray([sentence.type_ids for sentence in encoded], dtype=np.int64),
    }
    (tokens,) = session.run(["last_hidden_state"], inputs)
    mask = inputs["attention_mask"][..., None].astype(np.float32)
    pooled = (tokens * mask).sum(axis=1) / mask.sum(axis=1)
    return pooled / np.linalg.norm(pooled, axis=1, keepdims=True)


def load_reference(cache):
    cache.mkdir(parents=True, exist_ok=True)
    model = model_file(cache, "onnx/model.onnx", MODEL_SHA256)
    tokenizer_path = model_file(cache, "tokenizer.json", TOKENIZER_SHA256)
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    tokenizer.enable_truncation(max_length=256)
    tokenizer.enable_padding()
    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    if session.get_providers() != ["CPUExecutionProvider"]:
        raise RuntimeError(f"Unexpected execution providers: {session.get_providers()}")
    return session, tokenizer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model_cache", type=Path, help="Directory to hold the verified model and tokenizer")
    session, tokenizer = load_reference(parser.parse_args().model_cache)

    embeddings = embed(session, tokenizer, SENTENCES)
    if embeddings.shape != (len(SENTENCES), 384) or embeddings.dtype != np.float32:
        raise ValueError(f"Unexpected embedding shape or precision: {embeddings.shape}, {embeddings.dtype}")
    if not np.isfinite(embeddings).all():
        raise ValueError("Non-finite embedding components")
    np.testing.assert_allclose(np.linalg.norm(embeddings, axis=1), 1, rtol=0, atol=1e-6)
    np.testing.assert_allclose(embeddings[0], embed(session, tokenizer, SENTENCES[:1])[0], rtol=1e-4, atol=1e-5)
    if embeddings[0] @ embeddings[1] <= embeddings[0] @ embeddings[2]:
        raise ValueError("The reference model did not rank the related input first")

    print(json.dumps({
        "check": "inference-cpu-reference",
        "model": MODEL,
        "revision": REVISION,
        "model_sha256": MODEL_SHA256,
        "tokenizer_sha256": TOKENIZER_SHA256,
        "onnxruntime": ort.__version__,
        "providers": session.get_providers(),
        "precision": str(embeddings.dtype),
        "shape": list(embeddings.shape),
        "system": platform.system(),
        "machine": platform.machine(),
    }, indent=2))


if __name__ == "__main__":
    main()
