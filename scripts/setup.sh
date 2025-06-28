#!/usr/bin/env bash
# Safe, idempotent setup script for local dev and CI.
# Usage:
#   bash ./scripts/setup.sh [--ci]

set -Eeuo pipefail
IFS=$'\n\t'

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    echo "Please run this script (bash ./scripts/setup.sh), do not source it."
    return 1 2>/dev/null || exit 1
fi

# -----------------------------
# Configuration (overridable)
# -----------------------------
JAVA_VERSION="${JAVA_VERSION:-17.0.14-tem}"
SBT_VERSION="${SBT_VERSION:-1.10.11}"
SCALA_VERSION="${SCALA_VERSION:-2.13.16}"

OSS_CAD_SUITE_VERSION="${OSS_CAD_SUITE_VERSION:-2025-07-09}"
# Optional integrity check: export OSS_CAD_SUITE_SHA256=<expected_sha256>
OSS_CAD_SUITE_SHA256="${OSS_CAD_SUITE_SHA256:-}"

ESPRESSO_REPO="${ESPRESSO_REPO:-https://github.com/chipsalliance/espresso.git}"
# Pin to a branch/tag/commit if desired, e.g. ESPRESSO_REF=master or vX.Y or <commit>
ESPRESSO_REF="${ESPRESSO_REF:-master}"

INSTALL_PREFIX="${INSTALL_PREFIX:-$HOME/.local}" # user-local install prefix (no sudo)

# Parse args
CI_MODE=false
for arg in "$@"; do
    case "$arg" in
    --ci) CI_MODE=true ;;
    *)
        echo "Unknown argument: $arg"
        exit 2
        ;;
    esac
done
# If running in GitHub Actions, treat as CI mode
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then CI_MODE=true; fi

# -----------------------------
# Helpers
# -----------------------------
INITIAL_DIR="$(pwd)"
TMPDIR="$(mktemp -d)"

cleanup() {
    cd "$INITIAL_DIR" || true
    rm -rf "$TMPDIR"
}
trap cleanup EXIT

log() { printf '[%s] %s\n' "$(date +'%H:%M:%S')" "$*"; }

append_if_missing() {
    local line="$1" file="$2"
    mkdir -p "$(dirname "$file")"
    touch "$file"
    grep -qxF "$line" "$file" 2>/dev/null || echo "$line" >>"$file"
}

JOBS="$( (command -v nproc >/dev/null 2>&1 && nproc) || (getconf _NPROCESSORS_ONLN 2>/dev/null) || echo 2)"

# Determine profile to update for local shells
PROFILE_FILE="$HOME/.bashrc"
if [[ -n "${ZSH_VERSION:-}" || "${SHELL##*/}" == "zsh" ]]; then
    PROFILE_FILE="$HOME/.zshrc"
fi

# -----------------------------
# OS/arch detection
# -----------------------------
if [[ "$(uname -s)" != "Linux" ]]; then
    echo "This setup currently supports Linux only."
    exit 1
fi

arch="$(uname -m)"
case "$arch" in
x86_64 | amd64) oss_arch="linux-x64" ;;
aarch64 | arm64) oss_arch="linux-arm64" ;;
*)
    echo "Unsupported architecture: $arch"
    exit 1
    ;;
esac

# -----------------------------
# APT packages (Debian/Ubuntu)
# -----------------------------
if [[ -r /etc/os-release ]]; then . /etc/os-release; fi
if [[ "${ID_LIKE:-}${ID:-}" =~ (debian|ubuntu) ]]; then
    export DEBIAN_FRONTEND=noninteractive

    # Choose how to run apt-get: as root, with sudo, or skip if neither
    APT_PREFIX=()
    if [[ "$(id -u)" -ne 0 ]]; then
        if command -v sudo >/dev/null 2>&1; then
            if sudo -n true 2>/dev/null; then
                APT_PREFIX=(sudo -n)
            else
                APT_PREFIX=(sudo)
            fi
        fi
    fi

    if [[ "$(id -u)" -eq 0 || ${#APT_PREFIX[@]} -gt 0 ]]; then
        log "Installing system build dependencies via apt-get..."
        "${APT_PREFIX[@]}" apt-get update -y
        "${APT_PREFIX[@]}" apt-get install -y --no-install-recommends \
            build-essential zip unzip curl git wget cmake ca-certificates tar pkg-config
    else
        log "No sudo and not root; skipping apt-get. Please ensure these are installed:"
        log "  build-essential zip unzip curl git wget cmake ca-certificates tar pkg-config"
    fi
else
    log "Non-Debian-like OS detected; please ensure build tools, curl, git, cmake are installed."
fi

# -----------------------------
# SDKMAN + Java/sbt/Scala
# -----------------------------
if [[ ! -d "$HOME/.sdkman" ]]; then
    log "Installing SDKMAN..."
    curl -fsSL "https://get.sdkman.io" | bash
fi

log "Installing Java $JAVA_VERSION, sbt $SBT_VERSION, Scala $SCALA_VERSION via SDKMAN..."
(
    # Disable nounset inside SDKMAN to avoid unbound var errors
    set +u

    # shellcheck disable=SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh"

    sdk install java "$JAVA_VERSION" || sdk use java "$JAVA_VERSION" || true
    sdk install sbt "$SBT_VERSION" || true
    sdk install scala "$SCALA_VERSION" || true

    sdk default java "$JAVA_VERSION" || true
    sdk default sbt "$SBT_VERSION" || true
    sdk default scala "$SCALA_VERSION" || true
)

# Ensure user-local bin is present and on PATH in future shells
mkdir -p "$INSTALL_PREFIX/bin"
append_if_missing 'export PATH="$HOME/.local/bin:$PATH"' "$PROFILE_FILE"

# In CI, expose Java/sbt/scala without needing to source sdkman-init.sh
if $CI_MODE; then
    log "Configuring PATH and shims for CI..."
    # Make SDKMAN tools available via ~/.local/bin
    ln -sf "$HOME/.sdkman/candidates/sbt/current/bin/sbt" "$INSTALL_PREFIX/bin/sbt"
    ln -sf "$HOME/.sdkman/candidates/scala/current/bin/scala" "$INSTALL_PREFIX/bin/scala"
    ln -sf "$HOME/.sdkman/candidates/scala/current/bin/scalac" "$INSTALL_PREFIX/bin/scalac"
    ln -sf "$HOME/.sdkman/candidates/java/current/bin/java" "$INSTALL_PREFIX/bin/java"
    ln -sf "$HOME/.sdkman/candidates/java/current/bin/javac" "$INSTALL_PREFIX/bin/javac"
    # Persist PATH for subsequent steps
    if [[ -n "${GITHUB_PATH:-}" ]]; then
        echo "$INSTALL_PREFIX/bin" >>"$GITHUB_PATH"
    fi
fi

# -----------------------------
# OSS CAD Suite
# -----------------------------
OSS_CAD_SUITE_VERSION_COMPACT="${OSS_CAD_SUITE_VERSION//-/}"
OSS_CAD_SUITE_FILE="oss-cad-suite-${oss_arch}-${OSS_CAD_SUITE_VERSION_COMPACT}.tgz"
OSS_CAD_SUITE_URL="https://github.com/YosysHQ/oss-cad-suite-build/releases/download/${OSS_CAD_SUITE_VERSION}/${OSS_CAD_SUITE_FILE}"

OSS_CAD_DIR="$HOME/oss-cad-suite-${OSS_CAD_SUITE_VERSION_COMPACT}"
OSS_CAD_LINK="$HOME/oss-cad-suite"

if [[ ! -d "$OSS_CAD_DIR" ]]; then
    log "Installing OSS CAD Suite $OSS_CAD_SUITE_VERSION ($oss_arch)..."
    curl -fL --retry 5 --retry-delay 2 -o "$TMPDIR/$OSS_CAD_SUITE_FILE" "$OSS_CAD_SUITE_URL"
    if [[ -n "$OSS_CAD_SUITE_SHA256" ]]; then
        echo "$OSS_CAD_SUITE_SHA256  $TMPDIR/$OSS_CAD_SUITE_FILE" | sha256sum -c -
    fi
    tar -xzf "$TMPDIR/$OSS_CAD_SUITE_FILE" -C "$TMPDIR"
    # Archive extracts into $TMPDIR/oss-cad-suite
    mv "$TMPDIR/oss-cad-suite" "$OSS_CAD_DIR"
else
    log "OSS CAD Suite already present at $OSS_CAD_DIR"
fi

# Maintain a stable symlink
ln -sfn "$OSS_CAD_DIR" "$OSS_CAD_LINK"

# Persist environment setup for interactive shells
append_if_missing "source \"$OSS_CAD_LINK/environment\"" "$PROFILE_FILE"

# In CI, ensure binaries are on PATH; sourcing env is still recommended for tests
if $CI_MODE && [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "$OSS_CAD_LINK/bin" >>"$GITHUB_PATH"
fi

# -----------------------------
# Espresso (user-local install)
# -----------------------------
ESPRESSO_DIR="$HOME/espresso"

if [[ ! -d "$ESPRESSO_DIR" ]]; then
    log "Cloning Espresso ($ESPRESSO_REF)..."
    git clone --depth 1 --branch "$ESPRESSO_REF" "$ESPRESSO_REPO" "$ESPRESSO_DIR"
else
    log "Updating Espresso repository..."
    git -C "$ESPRESSO_DIR" fetch --depth 1 origin "$ESPRESSO_REF" || true
    # Try to checkout the ref; if it fails, remain on current
    git -C "$ESPRESSO_DIR" checkout -q "$ESPRESSO_REF" 2>/dev/null || true
    git -C "$ESPRESSO_DIR" reset --hard "origin/$ESPRESSO_REF" 2>/dev/null || true
fi

log "Building and installing Espresso (Release) to $INSTALL_PREFIX ..."
cmake -S "$ESPRESSO_DIR" -B "$ESPRESSO_DIR/build" \
    -DBUILD_DOC=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX"
cmake --build "$ESPRESSO_DIR/build" --parallel "$JOBS"
cmake --install "$ESPRESSO_DIR/build"

# Ensure ~/.local/bin is on PATH for the current shell if running interactively
if ! $CI_MODE; then
    case ":${PATH:=$PATH}:" in
    *":$INSTALL_PREFIX/bin:"*) : ;; # already present
    *) export PATH="$INSTALL_PREFIX/bin:$PATH" ;;
    esac
fi

log "Setup complete!"
log "Notes:"
log "- Open a new shell or run: source \"$PROFILE_FILE\" to load PATH/env updates."
log "- In CI, PATH has been updated via GITHUB_PATH; OSS CAD env is typically sourced per step as needed."
