#!/usr/bin/env python3
"""Validate and assemble an Android release APK without building or signing it."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Dict, Optional, Sequence


EXPECTED_PACKAGE = "xyz.chouxuewei.mobile_agent"
OUTPUT_APK_NAME = "app-release.apk"
SHA256SUMS_NAME = "SHA256SUMS"
RELEASE_INFO_NAME = "release-info.json"


class ReleaseError(Exception):
    """An expected validation or packaging error."""


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate an existing Android release APK and assemble release artifacts."
    )
    parser.add_argument("--apk", required=True, help="Path to the generated release APK")
    parser.add_argument(
        "--build-tools",
        required=True,
        help="Android SDK build-tools directory containing aapt and apksigner",
    )
    parser.add_argument("--version", required=True, help="Expected versionName")
    parser.add_argument("--version-code", required=True, help="Expected versionCode")
    parser.add_argument(
        "--expected-cert",
        required=True,
        help="Expected signer SHA-256 certificate digest (64 hexadecimal characters)",
    )
    parser.add_argument(
        "--revision",
        required=True,
        help="Source Git commit revision (40 hexadecimal characters)",
    )
    parser.add_argument("--output-dir", required=True, help="Directory for release artifacts")
    return parser


def _validate_arguments(args: argparse.Namespace) -> tuple[Path, Path, Path, int, str, str]:
    if not args.version:
        raise ReleaseError("version must not be empty")

    if not re.fullmatch(r"[0-9]+", args.version_code):
        raise ReleaseError("version-code must be a decimal integer")
    expected_version_code = int(args.version_code, 10)

    expected_cert = args.expected_cert.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_cert):
        raise ReleaseError("expected-cert must contain exactly 64 hexadecimal characters")

    revision = args.revision.lower()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ReleaseError("revision must contain exactly 40 hexadecimal characters")

    apk = Path(args.apk)
    if not apk.is_file():
        raise ReleaseError("APK does not exist or is not a file")

    build_tools = Path(args.build_tools)
    if not build_tools.is_dir():
        raise ReleaseError("build-tools must be a directory")

    aapt = build_tools / "aapt"
    apksigner = build_tools / "apksigner"
    if not aapt.is_file() or not apksigner.is_file():
        raise ReleaseError("build-tools must contain aapt and apksigner")

    output_dir = Path(args.output_dir)
    if output_dir.exists() and not output_dir.is_dir():
        raise ReleaseError("output-dir must be a directory")

    return apk, aapt, apksigner, expected_version_code, expected_cert, revision


def _run_tool(command: Sequence[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            list(command),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    except OSError as exc:
        raise ReleaseError("failed to run Android SDK validation tool") from exc


def _parse_quoted_attributes(line: str) -> Dict[str, str]:
    attributes: Dict[str, str] = {}
    pattern = re.compile(
        r"([A-Za-z][A-Za-z0-9_]*)\s*=\s*(?:'([^']*)'|\"([^\"]*)\"|(\S+))"
    )
    for match in pattern.finditer(line):
        value = match.group(2) or match.group(3) or match.group(4)
        attributes[match.group(1)] = value
    return attributes


def _parse_sdk_value(output: str, field_name: str) -> int:
    pattern = re.compile(
        rf"^\s*{re.escape(field_name)}\s*:\s*['\"]([^'\"]+)['\"]\s*$"
    )
    for line in output.splitlines():
        match = pattern.match(line.strip())
        if match:
            try:
                return int(match.group(1), 10)
            except ValueError as exc:
                raise ReleaseError(f"aapt returned an invalid {field_name}") from exc
    raise ReleaseError(f"aapt output is missing {field_name}")


def _parse_badging(
    output: str, expected_version: str, expected_version_code: int
) -> tuple[str, str, int, int, int]:
    package_attributes: Optional[Dict[str, str]] = None
    for line in output.splitlines():
        if line.lstrip().startswith("package:"):
            package_attributes = _parse_quoted_attributes(line)
            break

    if package_attributes is None:
        raise ReleaseError("aapt output is missing package metadata")

    package_name = package_attributes.get("name")
    version_name = package_attributes.get("versionName")
    version_code_text = package_attributes.get("versionCode")
    if not package_name or version_name is None or version_code_text is None:
        raise ReleaseError("aapt package metadata is incomplete")

    try:
        version_code = int(version_code_text, 10)
    except ValueError as exc:
        raise ReleaseError("aapt returned an invalid versionCode") from exc

    if package_name != EXPECTED_PACKAGE:
        raise ReleaseError("APK package name does not match the release package")
    if version_name != expected_version:
        raise ReleaseError("APK versionName does not match the requested version")
    if version_code != expected_version_code:
        raise ReleaseError("APK versionCode does not match the requested version code")

    if any(line.strip().startswith("application-debuggable") for line in output.splitlines()):
        raise ReleaseError("debuggable APKs are not accepted as release artifacts")

    min_sdk = _parse_sdk_value(output, "sdkVersion")
    target_sdk = _parse_sdk_value(output, "targetSdkVersion")
    return package_name, version_name, version_code, min_sdk, target_sdk


def _parse_signing_certificate(output: str, expected_cert: str) -> str:
    pattern = re.compile(
        r"certificate\s+SHA-256\s+digest\s*:\s*([0-9a-fA-F:]+)", re.IGNORECASE
    )
    certificates = {
        match.group(1).replace(":", "").lower()
        for match in pattern.finditer(output)
    }
    certificates = {value for value in certificates if re.fullmatch(r"[0-9a-f]{64}", value)}
    if not certificates:
        raise ReleaseError("signature output is missing a SHA-256 certificate digest")
    if len(certificates) != 1:
        raise ReleaseError("APK has more than one signing certificate")

    certificate = next(iter(certificates))
    if certificate != expected_cert:
        raise ReleaseError("APK signing certificate does not match expected-cert")
    return certificate


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise ReleaseError("failed to read APK") from exc
    return digest.hexdigest()


def _same_file(first: Path, second: Path) -> bool:
    try:
        return os.path.samefile(first, second)
    except (FileNotFoundError, OSError):
        return first.resolve(strict=False) == second.resolve(strict=False)


def _write_text(path: Path, content: str) -> None:
    try:
        path.write_text(content, encoding="utf-8")
    except OSError as exc:
        raise ReleaseError("failed to write release artifacts") from exc


def _publish(
    apk: Path,
    output_dir: Path,
    sha256: str,
    release_info: Dict[str, object],
) -> None:
    output_apk = output_dir / OUTPUT_APK_NAME
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        temp_dir = Path(tempfile.mkdtemp(prefix=".package-release-", dir=str(output_dir)))
    except OSError as exc:
        raise ReleaseError("failed to create output directory") from exc

    try:
        staged_apk = temp_dir / OUTPUT_APK_NAME
        staged_sums = temp_dir / SHA256SUMS_NAME
        staged_info = temp_dir / RELEASE_INFO_NAME

        same_apk = _same_file(apk, output_apk) if output_apk.exists() else False
        if not same_apk:
            try:
                shutil.copyfile(apk, staged_apk)
            except OSError as exc:
                raise ReleaseError("failed to copy APK into output directory") from exc

        _write_text(staged_sums, f"{sha256}  {OUTPUT_APK_NAME}\n")
        _write_text(
            staged_info,
            json.dumps(release_info, ensure_ascii=False, indent=2) + "\n",
        )

        try:
            if not same_apk:
                os.replace(staged_apk, output_apk)
            os.replace(staged_sums, output_dir / SHA256SUMS_NAME)
            os.replace(staged_info, output_dir / RELEASE_INFO_NAME)
        except OSError as exc:
            raise ReleaseError("failed to publish release artifacts") from exc
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def package_release(args: argparse.Namespace) -> None:
    apk, aapt, apksigner, expected_version_code, expected_cert, revision = _validate_arguments(
        args
    )
    output_dir = Path(args.output_dir)

    aapt_result = _run_tool([str(aapt), "dump", "badging", str(apk)])
    if aapt_result.returncode != 0:
        raise ReleaseError("aapt badging failed")
    package_name, version_name, version_code, min_sdk, target_sdk = _parse_badging(
        aapt_result.stdout,
        args.version,
        expected_version_code,
    )

    apksigner_result = _run_tool(
        [str(apksigner), "verify", "--print-certs", str(apk)]
    )
    if apksigner_result.returncode != 0:
        raise ReleaseError("apksigner verification failed")
    signing_certificate = _parse_signing_certificate(
        apksigner_result.stdout + "\n" + apksigner_result.stderr,
        expected_cert,
    )

    sha256 = _sha256(apk)
    release_info: Dict[str, object] = {
        "packageName": package_name,
        "versionName": version_name,
        "versionCode": version_code,
        "minSdk": min_sdk,
        "targetSdk": target_sdk,
        "sha256": sha256,
        "signingCertificateSha256": signing_certificate,
        "sourceCommit": revision,
    }
    _publish(apk, output_dir, sha256, release_info)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        package_release(args)
    except ReleaseError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print("release artifacts created")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
