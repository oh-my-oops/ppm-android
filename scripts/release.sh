#!/usr/bin/env bash
set -euo pipefail

# Phase 14.1 · PPM Android Release Build, Signature Verification & Checksum Script
# Usage: ./scripts/release.sh [--version <X.Y.Z|android-vX.Y.Z>] --expected-cert-sha256 <hex> [--out-dir <dir>]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="${PPM_ANDROID_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
EXPECTED_CERT_SHA256="${PPM_RELEASE_CERT_SHA256:-}"
RAW_VERSION="${PPM_VERSION:-}"
OUT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --expected-cert-sha256)
      EXPECTED_CERT_SHA256="${2:-}"
      shift 2
      ;;
    --version)
      RAW_VERSION="${2:-}"
      shift 2
      ;;
    --out-dir)
      OUT_DIR="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$RAW_VERSION" ]]; then
  RAW_VERSION="$(git -C "$ANDROID_DIR" describe --exact-match --tags HEAD 2>/dev/null || true)"
fi

VERSION="${RAW_VERSION#android-v}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: Release version must come from an android-vX.Y.Z tag or --version X.Y.Z (got '${RAW_VERSION:-<none>}')." >&2
  exit 1
fi

NORMALIZED_EXPECTED_SHA256="$(tr -d ':[:space:]' <<<"$EXPECTED_CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
if [[ ! "$NORMALIZED_EXPECTED_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "ERROR: --expected-cert-sha256 (or PPM_RELEASE_CERT_SHA256) is mandatory and must be a 64-character hex SHA-256 digest." >&2
  exit 1
fi

APKSIGNER_BIN="${APKSIGNER_BIN:-}"
if [[ -z "$APKSIGNER_BIN" ]]; then
  if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER_BIN="$(command -v apksigner)"
  else
    SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    if [[ -d "$SDK_ROOT/build-tools" ]]; then
      APKSIGNER_BIN="$(find "$SDK_ROOT/build-tools" -name apksigner | sort -V | tail -n 1 || true)"
    fi
  fi
fi

if [[ -z "$APKSIGNER_BIN" || ! -x "$APKSIGNER_BIN" ]]; then
  echo "ERROR: apksigner not found; cannot verify release APK signature." >&2
  exit 1
fi

GRADLEW_BIN="${GRADLEW_BIN:-$ANDROID_DIR/gradlew}"
echo "==> Building release APK (:app:assembleRelease -PppmVersion=$VERSION)..."
"$GRADLEW_BIN" -p "$ANDROID_DIR" :app:assembleRelease "-PppmVersion=$VERSION" --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.workers.max=2

APK_DIR="$ANDROID_DIR/app/build/outputs/apk/release"
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 2 -name "*.apk" | sort)

if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "ERROR: No release APK found in $APK_DIR" >&2
  exit 1
fi

for apk in "${APKS[@]}"; do
  echo "==> Verifying APK Signature Scheme v2/v3 with $APKSIGNER_BIN: $apk"
  CERT_OUT="$("$APKSIGNER_BIN" verify --verbose --print-certs "$apk")"
  echo "$CERT_OUT"

  if grep -qiE 'CN=Android Debug|O=Android,.*CN=Android' <<<"$CERT_OUT"; then
    echo "ERROR: APK is signed with a debug certificate; refusing release." >&2
    exit 1
  fi

  NORMALIZED_CERT_OUT="$(tr -d ':' <<<"$CERT_OUT" | tr '[:upper:]' '[:lower:]')"
  if ! grep -Fq "$NORMALIZED_EXPECTED_SHA256" <<<"$NORMALIZED_CERT_OUT"; then
    echo "ERROR: APK certificate SHA-256 does not match expected $NORMALIZED_EXPECTED_SHA256" >&2
    exit 1
  fi
  echo "==> Certificate SHA-256 matches expected fingerprint."
  sha256sum "$apk" | tee "${apk}.sha256"
done

OUT_DIR="${OUT_DIR:-$ANDROID_DIR/app/build/release-assets}"
RELEASE_ASSETS_PY="${RELEASE_ASSETS_PY:-$SCRIPT_DIR/release-assets.py}"
if [[ -f "$RELEASE_ASSETS_PY" && -f "$APK_DIR/output-metadata.json" ]]; then
  echo "==> Staging release assets via release-assets.py into $OUT_DIR..."
  python3 -I "$RELEASE_ASSETS_PY" "$OUT_DIR"
  for asset in "$OUT_DIR"/*; do
    if [[ -f "$asset" && "$asset" != *.sha256 ]]; then
      (cd "$OUT_DIR" && sha256sum "$(basename "$asset")" > "$(basename "$asset").sha256")
    fi
  done
fi

echo "==> Release build and verification completed successfully for v$VERSION."
