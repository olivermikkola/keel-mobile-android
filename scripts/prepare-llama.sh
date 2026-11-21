#!/usr/bin/env bash
# Fetches or links pinned upstream llama.cpp b12367 for native Android JNI builds.
# Supports Bonsai Q2_0, LFM3, MiniCPM5, and EmbeddingGemma.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="$ROOT_DIR/third_party/llama.cpp"
VERSION="b12367"

if [[ -f "$TARGET_DIR/CMakeLists.txt" ]]; then
  echo "llama.cpp already present at $TARGET_DIR"
  exit 0
fi

# Reuse a local b12367 checkout if already on this machine (avoids re-downloading).
if [[ -f "$HOME/Projects/llama.cpp-$VERSION/CMakeLists.txt" ]]; then
  mkdir -p "$ROOT_DIR/third_party"
  ln -s "$HOME/Projects/llama.cpp-$VERSION" "$TARGET_DIR"
  echo "Linked local llama.cpp $VERSION from $HOME/Projects/llama.cpp-$VERSION"
  exit 0
fi

mkdir -p "$ROOT_DIR/third_party"
ARCHIVE="$ROOT_DIR/third_party/llama-$VERSION.tar.gz"
curl --fail --location --retry 3 \
  "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$VERSION.tar.gz" \
  -o "$ARCHIVE.partial"
mv "$ARCHIVE.partial" "$ARCHIVE"
mkdir -p "$TARGET_DIR"
tar -xzf "$ARCHIVE" -C "$TARGET_DIR" --strip-components=1
rm -f "$ARCHIVE"
echo "Extracted llama.cpp $VERSION to $TARGET_DIR"
