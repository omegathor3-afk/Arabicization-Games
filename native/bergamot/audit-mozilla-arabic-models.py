#!/usr/bin/env python3
"""Fetch, hash, unpack and prepare Mozilla Arabic Bergamot base-memory models."""
from __future__ import annotations

import gzip
import hashlib
import json
import pathlib
import shutil
import urllib.request

REGISTRY_URL = (
    "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
)
LANGUAGES = ("ar-en", "en-ar")
CHUNK = 1024 * 1024


def digest_file(path: pathlib.Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def download(url: str, destination: pathlib.Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "ARABIFY-model-audit/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as out:
        shutil.copyfileobj(response, out, length=CHUNK)


def unpack_and_hash(source: pathlib.Path, destination: pathlib.Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with gzip.open(source, "rb") as compressed, destination.open("wb") as out:
        while True:
            chunk = compressed.read(CHUNK)
            if not chunk:
                break
            out.write(chunk)
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def main() -> None:
    with urllib.request.urlopen(REGISTRY_URL, timeout=45) as response:
        registry = json.load(response)
    base_url = registry["baseUrl"].rstrip("/")
    root = pathlib.Path("build/moz-models")
    root.mkdir(parents=True, exist_ok=True)
    result = {
        "registryUrl": REGISTRY_URL,
        "registryGenerated": registry.get("generated"),
        "hashAlgorithm": "SHA-256",
        "directions": {},
    }

    for direction in LANGUAGES:
        candidates = registry["models"].get(direction, [])
        entries = [
            entry for entry in candidates
            if entry.get("architecture") == "base-memory"
            and entry.get("releaseStatus") == "Release"
        ]
        if len(entries) != 1:
            raise RuntimeError(f"Expected exactly one base-memory Release entry for {direction}, got {len(entries)}")
        entry = entries[0]
        model_dir = root / direction
        if model_dir.exists():
            shutil.rmtree(model_dir)
        model_dir.mkdir(parents=True)
        files = {}
        unpacked_names = {}
        for kind in ("model", "lexicalShortlist", "vocab"):
            relative = entry["files"][kind]["path"]
            filename = pathlib.PurePosixPath(relative).name
            compressed_path = model_dir / filename
            unpacked_name = filename[:-3] if filename.endswith(".gz") else filename
            unpacked_path = model_dir / unpacked_name
            download(f"{base_url}/{relative}", compressed_path)
            compressed_hash, compressed_size = digest_file(compressed_path)
            unpacked_hash, unpacked_size = unpack_and_hash(compressed_path, unpacked_path)
            item = {
                "path": relative,
                "downloadedBytes": compressed_size,
                "downloadedSha256": compressed_hash,
                "uncompressedBytes": unpacked_size,
                "uncompressedSha256": unpacked_hash,
                "registrySha256": None,
                "registryHashScope": "not published; local downloaded artifact digest only",
                "unpackedFile": unpacked_name,
            }
            if kind == "model":
                expected_hash = entry["files"][kind].get("uncompressedHash")
                expected_size = entry["files"][kind].get("uncompressedSize")
                if not expected_hash or unpacked_hash != expected_hash:
                    raise RuntimeError(f"{direction} model uncompressed SHA-256 mismatch")
                if expected_size and unpacked_size != expected_size:
                    raise RuntimeError(f"{direction} model uncompressed size mismatch")
                item["registrySha256"] = expected_hash
                item["registryHashScope"] = "decompressed payload"
            files[kind] = item
            unpacked_names[kind] = unpacked_name

        model_name = unpacked_names["model"]
        vocab_name = unpacked_names["vocab"]
        shortlist_name = unpacked_names["lexicalShortlist"]
        config = "\n".join([
            "models:",
            f"  - {model_name}",
            "vocabs:",
            f"  - {vocab_name}",
            f"  - {vocab_name}",
            "shortlist:",
            f"  - {shortlist_name}",
            "  - false",
            "beam-size: 1",
            "normalize: 1.0",
            "word-penalty: 0",
            "max-length-break: 128",
            "mini-batch-words: 1024",
            "workspace: 128",
            "max-length-factor: 2.0",
            "skip-cost: true",
            "cpu-threads: 0",
            "quiet: true",
            "quiet-translation: true",
            "gemm-precision: int8shiftAlphaAll",
            "alignment: soft",
            "",
        ])
        (model_dir / "config.yml").write_text(config, encoding="utf-8")
        result["directions"][direction] = {
            "architecture": entry["architecture"],
            "releaseStatus": entry["releaseStatus"],
            "sourceLanguage": entry["sourceLanguage"],
            "targetLanguage": entry["targetLanguage"],
            "config": f"{direction}/config.yml",
            "files": files,
        }

    output = pathlib.Path("build/arabic-model-lock.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
