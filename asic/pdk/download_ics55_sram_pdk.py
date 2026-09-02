#!/usr/bin/env python3
"""Download an SRAM PDK package from ECOS Factory.

The script uses only Python's standard library. It validates the SRAM
configuration locally, calls the generate endpoint, downloads the returned
GitHub Release asset, and verifies its SHA-256 digest.

Examples:
    python3 download_ics55_sram_pdk.py
    python3 download_ics55_sram_pdk.py --words 4096 --bits 64 --mux 8
    python3 download_ics55_sram_pdk.py --preview --mux 16 --words 1024 --bits 32
    python3 download_ics55_sram_pdk.py --batch requests.json
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
import re
import socket
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "https://factory.openecos.com"
DEFAULT_USER_AGENT = "ecos-sram-pdk-downloader/1.0"
CHUNK_SIZE = 1024 * 1024

# mux: (minimum words, maximum words, word step, minimum bits, maximum bits)
MUX_RULES: dict[int, tuple[int, int, int, int, int]] = {
    4: (32, 4096, 32, 2, 160),
    8: (64, 8192, 64, 2, 80),
    16: (128, 16384, 128, 2, 40),
    32: (256, 32768, 256, 2, 20),
}

PVT_CORNERS = (
    "TT1P2V25CCTYP",
    "TT1P2V85CCTYP",
    "FF1P32VM40CCMIN",
    "FF1P32V0CCMIN",
    "FF1P32V125CCMIN",
    "SS1P08VM40CCMAX",
    "SS1P08V0CCMAX",
    "SS1P08V125CCMAX",
)
RINGS = ("ringless", "port", "ring")
CONFIG_FIELDS = (
    "words",
    "bits",
    "mux",
    "vt",
    "lowPower",
    "redundancy",
    "wordWrite",
    "busFormat",
    "ring",
    "corner",
)
DEFAULT_CONFIG: dict[str, Any] = {
    "words": 2048,
    "bits": 32,
    "mux": 8,
    "vt": 0,
    "lowPower": 0,
    "redundancy": 0,
    "wordWrite": 0,
    "busFormat": 1,
    "ring": "ringless",
    "corner": "TT1P2V25CCTYP",
}
CACHE_FILE_NAME = ".sram-download-cache.json"


class SramError(RuntimeError):
    """An expected configuration, API, or download failure."""


def validate_config(config: dict[str, Any]) -> None:
    """Validate a payload against the published SRAM contract."""
    integer_fields = (
        "words",
        "bits",
        "mux",
        "vt",
        "lowPower",
        "redundancy",
        "wordWrite",
        "busFormat",
    )
    for field in integer_fields:
        if type(config.get(field)) is not int:  # bool must not count as an int.
            raise SramError(f"{field} must be an integer")

    mux = config["mux"]
    if mux not in MUX_RULES:
        raise SramError(f"mux must be one of: {', '.join(map(str, MUX_RULES))}")

    words = config["words"]
    min_words, max_words, step, min_bits, max_bits = MUX_RULES[mux]
    if not min_words <= words <= max_words:
        raise SramError(
            f"words for mux={mux} must be between {min_words} and {max_words}"
        )
    if (words - min_words) % step != 0:
        raise SramError(f"words for mux={mux} must use a step of {step}")

    bits = config["bits"]
    if not min_bits <= bits <= max_bits:
        raise SramError(
            f"bits for mux={mux} must be between {min_bits} and {max_bits}"
        )

    allowed_values = {
        "vt": (0, 2, 5),
        "lowPower": (0, 2),
        "redundancy": (0, 3),
        "wordWrite": (0, 1),
        "busFormat": (0, 1),
    }
    for field, values in allowed_values.items():
        if config[field] not in values:
            allowed = ", ".join(map(str, values))
            raise SramError(f"{field} must be one of: {allowed}")

    if config.get("ring") not in RINGS:
        raise SramError(f"ring must be one of: {', '.join(RINGS)}")
    if config.get("corner") not in PVT_CORNERS:
        raise SramError(f"corner must be one of: {', '.join(PVT_CORNERS)}")


def build_payload(args: argparse.Namespace) -> dict[str, Any]:
    """Build the exact JSON payload expected by the API."""
    return {
        "words": args.words,
        "bits": args.bits,
        "mux": args.mux,
        "vt": args.vt,
        "lowPower": args.low_power,
        "redundancy": args.redundancy,
        "wordWrite": args.word_write,
        "busFormat": args.bus_format,
        "ring": args.ring,
        "corner": args.corner,
    }


@dataclass(frozen=True)
class CachedArtifact:
    asset_name: str
    archive_path: Path
    sha256: str
    legacy: bool = False


def _normalize_batch_item(item: Any, index: int) -> dict[str, Any]:
    if not isinstance(item, dict):
        raise SramError(f"batch item {index} must be a JSON object")
    unknown_fields = sorted(set(item) - set(CONFIG_FIELDS))
    if unknown_fields:
        fields = ", ".join(unknown_fields)
        raise SramError(f"batch item {index} has unsupported fields: {fields}")

    payload = DEFAULT_CONFIG.copy()
    payload.update(item)
    try:
        validate_config(payload)
    except SramError as error:
        raise SramError(f"batch item {index}: {error}") from error
    return payload


def load_batch_requests(path: Path) -> list[dict[str, Any]]:
    """Load a list of API payloads from a JSON batch request file."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise SramError(f"batch request file not found: {path}") from error
    except UnicodeDecodeError as error:
        raise SramError(f"batch request file is not valid UTF-8: {path}") from error
    except json.JSONDecodeError as error:
        raise SramError(
            f"batch request file contains invalid JSON at line {error.lineno}, column {error.colno}"
        ) from error
    except OSError as error:
        raise SramError(f"could not read batch request file {path}: {error}") from error

    if isinstance(document, dict):
        items = document.get("requests")
        if not isinstance(items, list):
            raise SramError("batch JSON object must contain a 'requests' array")
    elif isinstance(document, list):
        items = document
    else:
        raise SramError("batch request file must contain a JSON array or an object with 'requests'")

    if not items:
        raise SramError("batch request file must contain at least one request")
    return [_normalize_batch_item(item, index) for index, item in enumerate(items, start=1)]


def _read_cache_entries(output_dir: Path) -> list[dict[str, Any]]:
    cache_path = output_dir / CACHE_FILE_NAME
    if not cache_path.is_file():
        return []
    try:
        document = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return []
    entries = document.get("entries") if isinstance(document, dict) else None
    return entries if isinstance(entries, list) else []


def _legacy_asset_matches(asset_name: str, payload: dict[str, Any]) -> bool:
    """Match the legacy flat layout used before package subdirectories."""
    if payload["ring"] != "ringless":
        return False
    stem = asset_name
    for suffix in (".tar.gz", ".tgz", ".tar"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
            break
    expected_suffix = (
        f"_V{payload['vt']}L{payload['lowPower']}R{payload['redundancy']}"
        f"_{payload['words']}X{payload['bits']}M{payload['mux']}"
        f"W{payload['wordWrite']}F{payload['busFormat']}"
    )
    return stem.endswith(expected_suffix) and len(stem) > len(expected_suffix)


def find_cached_artifact(
    payload: dict[str, Any], output_dir: Path
) -> CachedArtifact | None:
    """Find a verified package locally before calling the generate endpoint."""
    for entry in _read_cache_entries(output_dir):
        if not isinstance(entry, dict) or entry.get("payload") != payload:
            continue
        asset_name = entry.get("assetName")
        expected_sha256 = entry.get("sha256")
        if (
            not isinstance(asset_name, str)
            or Path(asset_name).name != asset_name
            or "\\" in asset_name
            or not isinstance(expected_sha256, str)
            or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256)
        ):
            continue
        archive_path = output_dir / package_directory_name(asset_name) / asset_name
        if archive_path.is_file():
            try:
                actual_sha256 = sha256_file(archive_path)
            except OSError:
                continue
            if actual_sha256 == expected_sha256.lower():
                return CachedArtifact(asset_name, archive_path, expected_sha256.lower())

    if not output_dir.is_dir():
        return None
    try:
        candidates = sorted(output_dir.iterdir(), key=lambda path: path.name)
    except OSError:
        return None
    for candidate in candidates:
        if not candidate.is_file() or not _legacy_asset_matches(candidate.name, payload):
            continue
        try:
            if tarfile.is_tarfile(candidate):
                return CachedArtifact(
                    candidate.name, candidate, sha256_file(candidate), legacy=True
                )
        except (OSError, tarfile.TarError):
            continue
    return None


def organize_cached_artifact(
    artifact: CachedArtifact, output_dir: Path
) -> CachedArtifact:
    """Move a legacy flat archive into the current package directory layout."""
    package_dir = output_dir / package_directory_name(artifact.asset_name)
    destination = package_dir / artifact.asset_name
    if artifact.archive_path == destination:
        return artifact

    package_dir.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if destination.is_file() and sha256_file(destination) == artifact.sha256:
            return CachedArtifact(artifact.asset_name, destination, artifact.sha256)
        raise SramError(f"cached destination already exists with a different digest: {destination}")
    os.replace(artifact.archive_path, destination)
    return CachedArtifact(artifact.asset_name, destination, artifact.sha256)


def update_cache(
    output_dir: Path,
    payload: dict[str, Any],
    asset_name: str,
    sha256: str,
) -> None:
    """Persist enough metadata to identify this package without another API call."""
    output_dir.mkdir(parents=True, exist_ok=True)
    entries = [
        entry
        for entry in _read_cache_entries(output_dir)
        if not isinstance(entry, dict) or entry.get("payload") != payload
    ]
    entries.append({"payload": payload, "assetName": asset_name, "sha256": sha256})
    cache_path = output_dir / CACHE_FILE_NAME
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            prefix=f".{CACHE_FILE_NAME}.",
            suffix=".tmp",
            dir=output_dir,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            json.dump({"version": 1, "entries": entries}, temporary, indent=2)
            temporary.write("\n")
        os.replace(temporary_path, cache_path)
    except OSError as error:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise SramError(f"could not update local cache {cache_path}: {error}") from error


def _error_body(error: HTTPError) -> str:
    try:
        body = error.read().decode("utf-8", errors="replace").strip()
    except OSError:
        body = ""
    return f": {body[:500]}" if body else ""


def post_json(
    url: str,
    payload: dict[str, Any],
    *,
    timeout: float,
    user_agent: str,
) -> dict[str, Any]:
    """POST JSON and decode an object response."""
    request = Request(
        url,
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": user_agent,
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
    except HTTPError as error:
        raise SramError(f"POST {url} failed with HTTP {error.code}{_error_body(error)}") from error
    except (URLError, socket.timeout, TimeoutError, OSError) as error:
        raise SramError(f"POST {url} failed: {error}") from error

    try:
        decoded = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SramError(f"POST {url} returned invalid JSON") from error
    if not isinstance(decoded, dict):
        raise SramError(f"POST {url} returned JSON that is not an object")
    return decoded


def _validate_http_url(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise SramError(f"generate response field {field!r} is missing")
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise SramError(f"generate response field {field!r} is not an HTTP(S) URL")
    return value


def extract_artifact(response: dict[str, Any]) -> tuple[str, str | None, str, str]:
    """Extract and validate download metadata from a generate response."""
    if response.get("ok") is not True:
        detail = response.get("error") or response.get("message") or "unknown API error"
        raise SramError(f"generate request was rejected: {detail}")

    value = response.get("value")
    if not isinstance(value, dict):
        raise SramError("generate response does not contain an object value")

    download_url = _validate_http_url(value.get("downloadUrl"), "downloadUrl")
    mirror_value = value.get("mirrorUrl")
    mirror_url = None
    if mirror_value:
        mirror_url = _validate_http_url(mirror_value, "mirrorUrl")

    asset_name = value.get("assetName")
    if not isinstance(asset_name, str) or not asset_name:
        raise SramError("generate response field 'assetName' is missing")
    if (
        Path(asset_name).name != asset_name
        or "\\" in asset_name
        or asset_name in {".", ".."}
    ):
        raise SramError("assetName must be a plain file name")
    package_name = package_directory_name(asset_name)
    if package_name in {".", ".."}:
        raise SramError("assetName produces an unsafe package directory name")

    sha256 = value.get("sha256")
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha256):
        raise SramError("generate response field 'sha256' must be a 64-character hex digest")

    return download_url, mirror_url, asset_name, sha256.lower()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def package_directory_name(asset_name: str) -> str:
    """Return a stable directory name for a downloaded archive."""
    for suffix in (".tar.gz", ".tgz", ".tar"):
        if asset_name.endswith(suffix):
            return asset_name[: -len(suffix)] or "package"
    return Path(asset_name).stem or "package"


def _member_parts(member_name: str) -> tuple[str, ...]:
    """Validate a tar member name and return normalized POSIX path parts."""
    if not member_name or "\x00" in member_name or "\\" in member_name:
        raise SramError(f"unsafe archive member path: {member_name!r}")

    relative = PurePosixPath(member_name)
    if relative.is_absolute() or ".." in relative.parts:
        raise SramError(f"unsafe archive member path: {member_name!r}")
    return relative.parts


def _safe_member_path(
    root: Path, member_name: str, strip_root: str | None = None
) -> Path:
    """Resolve a tar member below root and reject unsafe paths."""
    parts = _member_parts(member_name)
    if strip_root is not None:
        if not parts or parts[0] != strip_root:
            raise SramError(f"archive member is outside the common root: {member_name!r}")
        parts = parts[1:]

    target = root.joinpath(*parts) if parts else root
    root_resolved = root.resolve()
    target_resolved = target.resolve(strict=False)
    try:
        inside_root = os.path.commonpath((str(root_resolved), str(target_resolved))) == str(
            root_resolved
        )
    except ValueError:
        inside_root = False
    if not inside_root:
        raise SramError(f"unsafe archive member path: {member_name!r}")
    return target


def extract_archive(archive_path: Path, destination: Path) -> int:
    """Safely extract a tar archive, stripping one common top-level directory."""
    destination.mkdir(parents=True, exist_ok=True)
    archive_resolved = archive_path.resolve()
    try:
        with tarfile.open(archive_path, mode="r:*") as archive:
            members = archive.getmembers()
            member_parts = [_member_parts(member.name) for member in members]
            strip_root: str | None = None
            archive_root_name = package_directory_name(archive_path.name)
            if member_parts and all(
                parts and parts[0] == member_parts[0][0] for parts in member_parts
            ):
                if (
                    any(len(parts) > 1 for parts in member_parts)
                    and member_parts[0][0] == archive_root_name
                ):
                    strip_root = member_parts[0][0]
            targets = [
                _safe_member_path(destination, member.name, strip_root) for member in members
            ]

            for member, target in zip(members, targets):
                if not (member.isdir() or member.isreg()):
                    raise SramError(
                        f"unsupported archive member type: {member.name!r}"
                    )
                if target.resolve(strict=False) == archive_resolved:
                    raise SramError("archive cannot overwrite its own downloaded file")

            extracted_files = 0
            for member, target in zip(members, targets):
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue

                target.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise SramError(f"could not read archive member: {member.name!r}")
                with source, target.open("wb") as stream:
                    while True:
                        chunk = source.read(CHUNK_SIZE)
                        if not chunk:
                            break
                        stream.write(chunk)
                if member.mode:
                    target.chmod(member.mode & 0o777)
                extracted_files += 1
            return extracted_files
    except SramError:
        raise
    except (OSError, tarfile.TarError, EOFError, RuntimeError) as error:
        raise SramError(f"could not extract {archive_path}: {error}") from error


def _download_once(
    url: str,
    destination: Path,
    *,
    timeout: float,
    user_agent: str,
) -> None:
    request = Request(
        url,
        headers={"Accept": "application/octet-stream", "User-Agent": user_agent},
    )
    try:
        with urlopen(request, timeout=timeout) as response, destination.open("wb") as stream:
            while True:
                chunk = response.read(CHUNK_SIZE)
                if not chunk:
                    break
                stream.write(chunk)
    except HTTPError as error:
        raise SramError(f"download failed with HTTP {error.code}{_error_body(error)}") from error
    except (URLError, socket.timeout, TimeoutError, OSError) as error:
        raise SramError(f"download failed: {error}") from error


def download_artifact(
    urls: Iterable[str],
    destination: Path,
    expected_sha256: str,
    *,
    timeout: float,
    user_agent: str,
    force: bool = False,
) -> bool:
    """Download, verify, and atomically install an artifact.

    Returns False when an existing file already has the requested digest.
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and not force:
        if destination.is_file() and sha256_file(destination) == expected_sha256:
            return False
        raise SramError(
            f"refusing to overwrite existing file with a different digest: {destination}"
        )

    errors: list[str] = []
    unique_urls = list(dict.fromkeys(urls))
    for url in unique_urls:
        temporary_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="wb",
                prefix=f".{destination.name}.",
                suffix=".part",
                dir=destination.parent,
                delete=False,
            ) as temporary:
                temporary_path = Path(temporary.name)
            _download_once(url, temporary_path, timeout=timeout, user_agent=user_agent)
            actual_sha256 = sha256_file(temporary_path)
            if actual_sha256 != expected_sha256:
                raise SramError(
                    f"SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}"
                )
            os.replace(temporary_path, destination)
            return True
        except (SramError, OSError) as error:
            errors.append(f"{url}: {error}")
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)

    joined = "\n  ".join(errors)
    raise SramError(f"all download URLs failed:\n  {joined}")


def process_package(
    payload: dict[str, Any], args: argparse.Namespace, label: str = ""
) -> None:
    """Generate or reuse one package, then download and extract it."""
    prefix = f"{label} " if label else ""
    cached = None if args.force else find_cached_artifact(payload, args.output_dir)
    if cached is not None:
        cached = organize_cached_artifact(cached, args.output_dir)
        asset_name = cached.asset_name
        package_dir = cached.archive_path.parent
        destination = cached.archive_path
        expected_sha256 = cached.sha256
        print(f"{prefix}Using cached archive: {destination}")
    else:
        response = post_json(
            f"{args.base_url.rstrip('/')}/api/ip/sram/generate",
            payload,
            timeout=args.timeout,
            user_agent=args.user_agent,
        )
        print(f"{prefix}Generate response:")
        print(json.dumps(response, ensure_ascii=False, indent=2))
        download_url, mirror_url, asset_name, expected_sha256 = extract_artifact(response)
        package_dir = args.output_dir / package_directory_name(asset_name)
        destination = package_dir / asset_name
        urls = [download_url]
        if mirror_url:
            urls.append(mirror_url)
        installed = download_artifact(
            urls,
            destination,
            expected_sha256,
            timeout=args.timeout,
            user_agent=args.user_agent,
            force=args.force,
        )
        if installed:
            print(f"{prefix}Downloaded and verified: {destination}")
        else:
            print(f"{prefix}Already verified, skipped download: {destination}")

    if args.no_extract:
        print(f"{prefix}Extraction skipped: {package_dir}")
    else:
        extracted_files = extract_archive(destination, package_dir)
        print(f"{prefix}Extracted {extracted_files} files to: {package_dir}")
    print(f"{prefix}SHA-256: {expected_sha256}")

    try:
        update_cache(args.output_dir, payload, asset_name, expected_sha256)
    except SramError as error:
        print(f"{prefix}warning: {error}", file=sys.stderr)


def download_package(
    payload: dict[str, Any],
    output_dir: Path,
    *,
    base_url: str = DEFAULT_BASE_URL,
    timeout: float = 60.0,
    user_agent: str = DEFAULT_USER_AGENT,
    force: bool = False,
) -> Path:
    """Download or reuse one verified package and return its extracted directory."""
    validate_config(payload)
    options = argparse.Namespace(
        output_dir=output_dir.expanduser().resolve(),
        base_url=base_url,
        timeout=timeout,
        user_agent=user_agent,
        force=force,
        no_extract=False,
    )
    process_package(payload, options)
    cached = find_cached_artifact(payload, options.output_dir)
    if cached is None:
        raise SramError("download completed without a discoverable cached artifact")
    return organize_cached_artifact(cached, options.output_dir).archive_path.parent


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate and download an SRAM PDK package from ECOS Factory."
    )
    parser.add_argument("--words", type=int, default=2048, help="memory depth (default: 2048)")
    parser.add_argument("--bits", type=int, default=32, help="data width (default: 32)")
    parser.add_argument("--mux", type=int, default=8, help="column mux: 4, 8, 16, or 32")
    parser.add_argument("--vt", type=int, default=0, help="VT option: 0, 2, or 5")
    parser.add_argument(
        "--low-power",
        "--lowPower",
        type=int,
        default=0,
        dest="low_power",
        help="low-power option: 0 or 2",
    )
    parser.add_argument(
        "--redundancy", type=int, default=0, help="repair option: 0 or 3"
    )
    parser.add_argument(
        "--word-write",
        "--wordWrite",
        type=int,
        default=0,
        dest="word_write",
        help="write mode: 0 or 1",
    )
    parser.add_argument(
        "--bus-format",
        "--busFormat",
        type=int,
        default=1,
        dest="bus_format",
        help="bus format: 0 or 1",
    )
    parser.add_argument(
        "--ring", choices=RINGS, default="ringless", help="ring option (default: ringless)"
    )
    parser.add_argument(
        "--corner",
        choices=PVT_CORNERS,
        default="TT1P2V25CCTYP",
        help="PVT corner used for preview (default: TT1P2V25CCTYP)",
    )
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help=f"ECOS Factory base URL (default: {DEFAULT_BASE_URL})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("downloads"),
        help="root directory for downloaded packages (default: ./downloads)",
    )
    parser.add_argument(
        "--batch",
        type=Path,
        help="read multiple SRAM requests from a JSON file",
    )
    parser.add_argument(
        "--no-extract",
        action="store_true",
        help="keep the archive without extracting it",
    )
    parser.add_argument(
        "--preview",
        action="store_true",
        help="call /preview and print its JSON without downloading",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="overwrite an existing artifact after verifying the new download",
    )
    parser.add_argument(
        "--timeout", type=float, default=60.0, help="HTTP timeout in seconds (default: 60)"
    )
    parser.add_argument(
        "--user-agent", default=DEFAULT_USER_AGENT, help="HTTP User-Agent header"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = make_parser().parse_args(argv)
    if args.timeout <= 0:
        print("error: --timeout must be greater than zero", file=sys.stderr)
        return 2
    if args.batch is not None and args.preview:
        print("error: --batch cannot be combined with --preview", file=sys.stderr)
        return 2

    if args.batch is not None:
        try:
            requests = load_batch_requests(args.batch)
        except SramError as error:
            print(f"error: {error}", file=sys.stderr)
            return 1

        failures: list[tuple[str, str]] = []
        succeeded = 0
        total = len(requests)
        for index, payload in enumerate(requests, start=1):
            label = f"[{index}/{total}]"
            try:
                process_package(payload, args, label)
                succeeded += 1
            except (SramError, OSError) as error:
                failures.append((label, str(error)))
                print(f"{label} error: {error}", file=sys.stderr)

        print(f"Batch complete: {succeeded}/{total} succeeded")
        if failures:
            print("Failed batch items:", file=sys.stderr)
            for label, error in failures:
                print(f"  {label}: {error}", file=sys.stderr)
            return 1
        return 0

    payload = build_payload(args)
    try:
        validate_config(payload)
        if args.preview:
            response = post_json(
                f"{args.base_url.rstrip('/')}/api/ip/sram/preview",
                payload,
                timeout=args.timeout,
                user_agent=args.user_agent,
            )
            print(json.dumps(response, ensure_ascii=False, indent=2))
            if response.get("ok") is not True:
                raise SramError("preview request was rejected")
            return 0

        process_package(payload, args)
        return 0
    except (SramError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
