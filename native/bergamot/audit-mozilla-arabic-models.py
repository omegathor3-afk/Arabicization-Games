#!/usr/bin/env python3
"""Download and audit Mozilla's released Arabic Bergamot model packages.

The Mozilla registry publishes an uncompressed SHA-256 for model weights, but
not for lexical shortlist or SentencePiece vocab. We record SHA-256 for the
exact downloaded bytes of all three files; only the model's decompressed hash
can be independently checked against a registry-provided digest.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import pathlib
import tempfile
import urllib.request

REGISTRY_URL = (
    "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
)
LANGUAGES = ("ar-en", "en-ar")
CHUNK = 1024 * 1024


def digest_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: pathlib.Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "ARABIFY-model-audit/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as out:
        while True:
            chunk = response.read(CHUNK)
            if not chunk:
                break
            out.write(chunk)


def decompressed_digest(path: pathlib.Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with gzip.open(path, "rb") as stream:
        while True:
            chunk = stream.read(CHUNK)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def main() -> None:
    with urllib.request.urlopen(REGISTRY_URL, timeout=45) as response:
        registry = json.load(response)
    base_url = registry["baseUrl"].rstrip("/")
    result = {
        "registryUrl": REGISTRY_URL,
        "registryGenerated": registry.get("generated"),
        "hashAlgorithm": "SHA-256",
        "directions": {},
    }

    with tempfile.TemporaryDirectory(prefix="arabify-mozilla-models-") as temp:
        root = pathlib.Path(temp)
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
            files = {}
            for kind in ("model", "lexicalShortlist", "vocab"):
                relative = entry["files"][kind]["path"]
                target = root / f"{direction}-{kind}.gz"
                download(f"{base_url}/{relative}", target)
                compressed_hash = digest_file(target)
                item = {
                    "path": relative,
                    "downloadedBytes": target.stat().st_size,
                    "downloadedSha256": compressed_hash,
                    "registrySha256": None,
                    "registryHashScope": None,
                }
                if kind == "model":
                    unpacked_hash, unpacked_size = decompressed_digest(target)
                    expected_hash = entry["files"][kind].get("uncompressedHash")
                    expected_size = entry["files"][kind].get("uncompressedSize")
                    if not expected_hash or unpacked_hash != expected_hash:
                        raise RuntimeError(f"{direction} model uncompressed SHA-256 mismatch")
                    if expected_size and unpacked_size != expected_size:
                        raise RuntimeError(f"{direction} model uncompressed size mismatch")
                    item.update({
                        "registrySha256": expected_hash,
                        "registryHashScope": "decompressed payload",
                        "uncompressedBytes": unpacked_size,
                        "uncompressedSha256": unpacked_hash,
                    })
                else:
                    item["registryHashScope"] = "no digest published in Mozilla registry; downloaded-byte digest is TOFU"
                files[kind] = item
            result["directions"][direction] = {
                "architecture": entry["architecture"],
                "releaseStatus": entry["releaseStatus"],
                "sourceLanguage": entry["sourceLanguage"],
                "targetLanguage": entry["targetLanguage"],
                "files": files,
            }

    output = pathlib.Path("build/arabic-model-lock.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
