#!/usr/bin/env python3
"""Create a read-only UnityPy oracle manifest for the pinned atlas_test UnityFS fixture."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import UnityPy


def normalized_path(raw: object) -> str:
    value = str(raw).replace("\\", "/")
    if not value or value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/")):
        raise ValueError(f"Unsafe UnityFS member path: {value!r}")
    return value


def raw_member_bytes(member: object) -> bytes:
    reader = getattr(member, "reader", None)
    if reader is not None and hasattr(reader, "bytes"):
        return bytes(reader.bytes)
    if hasattr(member, "bytes"):
        return bytes(member.bytes)
    raise TypeError(f"Unsupported UnityPy member type: {type(member).__name__}")


def normalize_endian(value: object) -> str:
    if value in ("<", "little", "LITTLE", 0, False):
        return "little"
    if value in (">", "big", "BIG", 1, True):
        return "big"
    raise ValueError(f"Unknown UnityPy SerializedFile endian value: {value!r}")


def escape_property(value: object) -> str:
    return (
        str(value)
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    )


def write_properties(path: Path, values: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as output:
        for key in sorted(values):
            output.write(f"{key}={escape_property(values[key])}\n")


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: create-unitypy-oracle.py BUNDLE PROPERTIES_FILE JSON_FILE")

    bundle_path = Path(sys.argv[1])
    properties_path = Path(sys.argv[2])
    json_path = Path(sys.argv[3])
    if not bundle_path.is_file():
        raise FileNotFoundError(bundle_path)

    environment = UnityPy.load(str(bundle_path))
    bundles = [
        item for item in environment.files.values()
        if getattr(item, "signature", None) == "UnityFS"
        and isinstance(getattr(item, "files", None), dict)
    ]
    if len(bundles) != 1:
        raise RuntimeError(f"Expected one UnityFS bundle, found {len(bundles)}")

    bundle = bundles[0]
    bundle_info = {
        "signature": str(bundle.signature),
        "formatVersion": int(bundle.version),
        "engineVersion": str(bundle.version_engine),
        "unityPyVersion": str(getattr(UnityPy, "__version__", "unknown")),
    }
    if bundle_info["unityPyVersion"] != "1.24.2":
        raise RuntimeError(f"Unexpected UnityPy oracle version: {bundle_info['unityPyVersion']}")
    if bundle_info["formatVersion"] != 6 or bundle_info["engineVersion"] != "2018.4.11f1":
        raise RuntimeError(f"Unexpected pinned fixture header: {bundle_info}")

    members: list[dict[str, object]] = []
    serialized_files: list[dict[str, object]] = []
    for raw_name, member in bundle.files.items():
        path = normalized_path(raw_name)
        data = raw_member_bytes(member)
        members.append({
            "path": path,
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })

        if all(hasattr(member, attr) for attr in (
            "header", "types", "objects", "externals", "unity_version"
        )):
            header = member.header
            class_ids = [int(item.class_id) for item in member.types]
            external_paths = [str(item.path) for item in member.externals]
            type_trees = []
            for type_record in member.types:
                root = getattr(type_record, "node", None)
                nodes = list(root.traverse()) if root is not None else []
                type_trees.append([
                    {
                        "version": int(node.m_Version),
                        "level": int(node.m_Level),
                        "typeFlags": int(getattr(node, "m_TypeFlags", 0) or 0),
                        "typeName": str(node.m_Type),
                        "fieldName": str(node.m_Name),
                        "byteSize": int(node.m_ByteSize),
                        "index": int(node.m_Index),
                        "metaFlags": int(getattr(node, "m_MetaFlag", 0) or 0),
                        "refTypeHash": (
                            "" if getattr(node, "m_RefTypeHash", None) is None
                            else int(node.m_RefTypeHash)
                        ),
                    }
                    for node in nodes
                ])
            serialized_files.append({
                "path": path,
                "headerVersion": int(header.version),
                "fileSize": int(header.file_size),
                "metadataSize": int(header.metadata_size),
                "dataOffset": int(header.data_offset),
                "endian": normalize_endian(header.endian),
                "unityVersion": str(member.unity_version),
                "targetPlatform": int(member._m_target_platform),
                "typeTreeEnabled": bool(member._enable_type_tree),
                "typeCount": len(member.types),
                "classIds": class_ids,
                "typeTrees": type_trees,
                "objectCount": len(member.objects),
                "externalPaths": external_paths,
            })

    members.sort(key=lambda item: str(item["path"]))
    serialized_files.sort(key=lambda item: str(item["path"]))
    if not members or not serialized_files:
        raise RuntimeError("Pinned UnityFS fixture did not contain raw members and SerializedFiles")

    total_bytes = sum(int(item["size"]) for item in members)
    oracle = {
        "bundle": bundle_info,
        "members": members,
        "totalBytes": total_bytes,
        "serializedFiles": serialized_files,
    }
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(oracle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    properties: dict[str, object] = {
        "oracle.unityPyVersion": bundle_info["unityPyVersion"],
        "bundle.signature": bundle_info["signature"],
        "bundle.formatVersion": bundle_info["formatVersion"],
        "bundle.engineVersion": bundle_info["engineVersion"],
        "members.count": len(members),
        "members.totalBytes": total_bytes,
        "serialized.count": len(serialized_files),
    }
    for index, member in enumerate(members):
        properties[f"member.{index}.path"] = member["path"]
        properties[f"member.{index}.size"] = member["size"]
        properties[f"member.{index}.sha256"] = member["sha256"]

    for index, item in enumerate(serialized_files):
        prefix = f"serialized.{index}"
        for key in (
            "path", "headerVersion", "fileSize", "metadataSize", "dataOffset",
            "endian", "unityVersion", "targetPlatform", "typeTreeEnabled",
            "typeCount", "objectCount",
        ):
            properties[f"{prefix}.{key}"] = item[key]
        class_ids = item["classIds"]
        properties[f"{prefix}.classCount"] = len(class_ids)
        for class_index, class_id in enumerate(class_ids):
            properties[f"{prefix}.class.{class_index}"] = class_id
        type_trees = item["typeTrees"]
        properties[f"{prefix}.typeTreeCount"] = len(type_trees)
        for type_index, nodes in enumerate(type_trees):
            type_prefix = f"{prefix}.type.{type_index}"
            properties[f"{type_prefix}.treeNodeCount"] = len(nodes)
            for node_index, node in enumerate(nodes):
                node_prefix = f"{type_prefix}.node.{node_index}"
                for key in (
                    "version", "level", "typeFlags", "typeName", "fieldName",
                    "byteSize", "index", "metaFlags", "refTypeHash",
                ):
                    properties[f"{node_prefix}.{key}"] = node[key]
        external_paths = item["externalPaths"]
        properties[f"{prefix}.externalCount"] = len(external_paths)
        for external_index, external_path in enumerate(external_paths):
            properties[f"{prefix}.external.{external_index}"] = external_path

    write_properties(properties_path, properties)
    print(
        f"UnityPy {bundle_info['unityPyVersion']} oracle: "
        f"{len(members)} members, {total_bytes} bytes, "
        f"{len(serialized_files)} SerializedFiles"
    )


if __name__ == "__main__":
    main()
