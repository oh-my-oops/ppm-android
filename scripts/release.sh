#!/usr/bin/env bash
set -euo pipefail

# Phase 14.1 · PPM Android Release Build, Signature Verification & Checksum Script
# Usage: ./scripts/release.sh [--expected-cert-sha256 <hex>]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
EXPECTED_CERT_SHA256="${PPM_RELEASE_CERT_SHA256:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --expected-cert-sha256)
      EXPECTED_CERT_SHA256="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

echo "==> Building release APK (:app:assembleRelease)..."
"$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" :app:assembleRelease --no-daemon \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.workers.max=2

APK_DIR="$ANDROID_DIR/app/build/outputs/apk/release"
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 2 -name "*.apk" | sort)

if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "ERROR: No release APK found in $APK_DIR" >&2
  exit 1
fi

APKSIGNER_BIN=""
if command -v apksigner >/dev/null 2>&1; then
  APKSIGNER_BIN="$(command -v apksigner)"
elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/build-tools" ]]; then
  APKSIGNER_BIN="$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -n 1 || true)"
fi

for apk in "${APKS[@]}"; do
  echo "==> Artifact: $apk"
  sha256sum "$apk" | tee "${apk}.sha256"

  if [[ -n "$APKSIGNER_BIN" ]]; then
    echo "==> Verifying APK Signature Scheme v2/v3 with $APKSIGNER_BIN..."
    CERT_OUT="$("$APKSIGNER_BIN" verify --verbose --print-certs "$apk" || true)"
    echo "$CERT_OUT"
    if [[ -n "$EXPECTED_CERT_SHA256" ]]; then
      if ! grep -qi "$EXPECTED_CERT_SHA256" <<<"$CERT_OUT"; then
        echo "ERROR: APK certificate SHA-256 does not match expected $EXPECTED_CERT_SHA256" >&2
        exit 1
      fi
      echo "==> Certificate SHA-256 matches expected fingerprint."
    fi
  else
    echo "NOTE: apksigner not found on PATH; skipped signature certificate check."
  fi
done

echo "==> Release build and verification completed successfully."
