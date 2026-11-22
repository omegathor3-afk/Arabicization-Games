#!/usr/bin/env python3
"""Create a UnityPy oracle for a real Unity 2019.4 TextAsset bundle fixture."""
from __future__ import annotations

import base64
import hashlib
import json
import sys
from pathlib import Path

import UnityPy


EXPECTED_SCRIPT = (
    "Hello from unity-asset-reader fixtures.\n"
    "Line 2: é€😀\n"
).encode("utf-8")


def normalized_path(raw: object) -> str:
    value = str(raw).replace("\\", "/")
    if not value or value.startswith("/") or any(
        part in ("", ".", "..") for part in value.split("/")
    ):
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
        raise SystemExit(
            "usage: create-unitypy-textasset-oracle.py BUNDLE PROPERTIES_FILE JSON_FILE"
        )

    bundle_path = Path(sys.argv[1])
    properties_path = Path(sys.argv[2])
    json_path = Path(sys.argv[3])
    if not bundle_path.is_file():
        raise FileNotFoundError(bundle_path)
    if getattr(UnityPy, "__version__", None) != "1.24.2":
        raise RuntimeError(f"Unexpected UnityPy oracle version: {getattr(UnityPy, '__version__', 'unknown')}")

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
        "unityPyVersion": str(UnityPy.__version__),
    }
    if bundle_info["formatVersion"] != 7 or bundle_info["engineVersion"] != "2019.4.41f2":
        raise RuntimeError(f"Unexpected pinned TextAsset fixture header: {bundle_info}")

    members: list[dict[str, object]] = []
    serialized_files: list[dict[str, object]] = []
    text_assets: list[dict[str, object]] = []

    for raw_name, member in bundle.files.items():
        path = normalized_path(raw_name)
        raw_bytes = raw_member_bytes(member)
        members.append({
            "path": path,
            "size": len(raw_bytes),
            "sha256": hashlib.sha256(raw_bytes).hexdigest(),
        })

        if not all(hasattr(member, attr) for attr in (
            "header", "types", "objects", "externals", "unity_version"
        )):
            continue

        header = member.header
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
            "classIds": [int(item.class_id) for item in member.types],
            "objectCount": len(member.objects),
        })

        for obj in member.objects.values():
            if int(obj.class_id) != 49:
                continue
            if not member._enable_type_tree or obj.serialized_type is None:
                raise RuntimeError("Pinned TextAsset fixture has no resolved TypeTree")

            parsed = obj.read_typetree()
            raw_script = parsed.get("m_Script")
            if isinstance(raw_script, str):
                script = raw_script.encode("utf-8")
            else:
                script = bytes(raw_script or b"")
            if script != EXPECTED_SCRIPT:
                raise RuntimeError(
                    "Pinned TextAsset script differs from the documented 58-byte UTF-8 fixture"
                )

            type_tree = getattr(obj.serialized_type, "node", None)
            if type_tree is None:
                raise RuntimeError("UnityPy did not resolve the TextAsset TypeTree")
            nodes = list(type_tree.traverse())
            text_assets.append({
                "serializedPath": path,
                "pathId": int(obj.path_id),
                "typeId": int(obj.type_id),
                "classId": int(obj.class_id),
                "byteStart": int(obj.byte_start - header.data_offset),
                "absoluteDataOffset": int(obj.byte_start),
                "byteSize": int(obj.byte_size),
                "name": str(parsed.get("m_Name", "")),
                "pathName": str(parsed.get("m_PathName") or ""),
                "scriptBase64": base64.b64encode(script).decode("ascii"),
                "scriptSha256": hashlib.sha256(script).hexdigest(),
                "typeTreeNodes": [
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
                ],
            })

    members.sort(key=lambda item: str(item["path"]))
    serialized_files.sort(key=lambda item: str(item["path"]))
    if not members or len(serialized_files) != 1 or len(text_assets) != 1:
        raise RuntimeError(
            "Expected raw UnityFS members, one SerializedFile and exactly one TextAsset; "
            f"found {len(members)} members, {len(serialized_files)} SerializedFiles, "
            f"{len(text_assets)} TextAssets"
        )

    serialized = serialized_files[0]
    text_asset = text_assets[0]
    if text_asset["serializedPath"] != serialized["path"]:
        raise RuntimeError("TextAsset was not found in the only SerializedFile")
    if serialized["headerVersion"] != 21 or not serialized["typeTreeEnabled"]:
        raise RuntimeError(f"Unexpected SerializedFile/TextAsset schema: {serialized}")

    total_bytes = sum(int(item["size"]) for item in members)
    oracle = {
        "bundle": bundle_info,
        "members": members,
        "totalBytes": total_bytes,
        "serializedFile": serialized,
        "textAsset": text_asset,
    }
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(
        json.dumps(oracle, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    properties: dict[str, object] = {
        "bundle.signature": bundle_info["signature"],
        "bundle.formatVersion": bundle_info["formatVersion"],
        "bundle.engineVersion": bundle_info["engineVersion"],
        "bundle.unityPyVersion": bundle_info["unityPyVersion"],
        "members.count": len(members),
        "members.totalBytes": total_bytes,
        "serialized.path": serialized["path"],
    }
    for index, member in enumerate(members):
        properties[f"member.{index}.path"] = member["path"]
        properties[f"member.{index}.size"] = member["size"]
        properties[f"member.{index}.sha256"] = member["sha256"]

    for key in (
        "headerVersion", "fileSize", "metadataSize", "dataOffset", "endian",
        "unityVersion", "targetPlatform", "typeTreeEnabled", "typeCount", "objectCount",
    ):
        properties[f"serialized.{key}"] = serialized[key]
    properties["serialized.classCount"] = len(serialized["classIds"])
    for index, class_id in enumerate(serialized["classIds"]):
        properties[f"serialized.class.{index}"] = class_id

    for key in (
        "pathId", "typeId", "classId", "byteStart", "absoluteDataOffset",
        "byteSize", "name", "pathName", "scriptBase64", "scriptSha256",
    ):
        properties[f"textAsset.{key}"] = text_asset[key]
    nodes = text_asset["typeTreeNodes"]
    properties["textAsset.typeTreeNodeCount"] = len(nodes)
    for index, node in enumerate(nodes):
        prefix = f"textAsset.typeTree.node.{index}"
        for key in (
            "version", "level", "typeFlags", "typeName", "fieldName",
            "byteSize", "index", "metaFlags", "refTypeHash",
        ):
            properties[f"{prefix}.{key}"] = node[key]

    write_properties(properties_path, properties)
    print(
        f"UnityPy {bundle_info['unityPyVersion']} TextAsset oracle: "
        f"Unity {bundle_info['engineVersion']}, SerializedFile v21, "
        f"{len(members)} members, {total_bytes} bytes, "
        f"{len(nodes)} TypeTree nodes, script {len(EXPECTED_SCRIPT)} bytes"
    )


if __name__ == "__main__":
    main()
