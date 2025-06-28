#!/usr/bin/env bash
# Safe, idempotent setup script for local dev and CI.
#
# Usage:
#   bash ./scripts/setup.sh [--ci] [--help]
#
# Environment overrides:
#   JAVA_VERSION=17.0.14-tem
#   SBT_VERSION=1.10.11
#   SCALA_VERSION=2.13.16
#   OSS_CAD_SUITE_VERSION=2025-07-09
#   OSS_CAD_SUITE_SHA256=<sha256>   # optional
#   ESPRESSO_REPO=https://github.com/chipsalliance/espresso.git
#   ESPRESSO_REF=master|<tag>|<commit>
#   INSTALL_PREFIX=$HOME/.local
#   NO_PROFILE=1                    # do not modify ~/.bashrc / ~/.zshrc

set -Eeuo pipefail
IFS=$'\n\t'

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    echo "Please run this script (bash ./scripts/setup.sh), do not source it."
    return 1 2>/dev/null || exit 1
fi

usage() {
    cat <<'EOF'
Usage: bash ./scripts/setup.sh [--ci] [--help]

Options:
  --ci        Non-interactive CI mode (adds shims via GITHUB_PATH).
  --help      Show this help.

Notes:
  - Linux only.
  - Installs user-local tooling under ~/.local and ~/.sdkman
    and downloads OSS CAD Suite under ~/oss-cad-suite-<version>.
  - Set NO_PROFILE=1 to avoid modifying shell rc files.
EOF
}

# -----------------------------
# Parse args / CI detection
# -----------------------------
CI_MODE=false
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    CI_MODE=true
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
    --ci)
        CI_MODE=true
        shift
        ;;
    -h | --help)
        usage
        exit 0
        ;;
    *)
        echo "Unknown argument: $1"
        echo
        usage
        exit 2
        ;;
    esac
done

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

# -----------------------------
# Helpers
# -----------------------------
INITIAL_DIR="$(pwd)"
TMP_DIR="$(mktemp -d)"

cleanup() {
    cd "$INITIAL_DIR" || true
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

log() { printf '[%s] %s\n' "$(date +'%H:%M:%S')" "$*"; }

append_if_missing() {
    local line="$1" file="$2"
    mkdir -p "$(dirname "$file")"
    touch "$file"
    grep -qxF "$line" "$file" 2>/dev/null || echo "$line" >>"$file"
}

require_cmd() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd"
        return 1
    fi
}

JOBS="$(
    (command -v nproc >/dev/null 2>&1 && nproc) || (getconf _NPROCESSORS_ONLN 2>/dev/null) || echo 2
)"

# Determine profile to update for local shells (skip in CI)
PROFILE_FILE="$HOME/.bashrc"
if [[ -n "${ZSH_VERSION:-}" || "${SHELL##*/}" == "zsh" ]]; then
    PROFILE_FILE="$HOME/.zshrc"
fi

UPDATE_PROFILE=true
if $CI_MODE; then UPDATE_PROFILE=false; fi
if [[ "${NO_PROFILE:-}" == "1" ]]; then UPDATE_PROFILE=false; fi

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
        log "Installing system build dependencies via apt-get."
        "${APT_PREFIX[@]}" apt-get update -y
        "${APT_PREFIX[@]}" apt-get install -y --no-install-recommends \
            build-essential \
            bison flex \
            zip unzip \
            curl git wget \
            cmake pkg-config \
            ca-certificates tar \
            python3 python3-pip \
            verilator
    else
        log "No sudo and not root; skipping apt-get. Please ensure these are installed:"
        log "  build-essential bison flex zip unzip curl git wget cmake pkg-config ca-certificates tar python3 python3-pip verilator"
    fi
else
    log "Non-Debian-like OS detected; please ensure build tools, curl, git, cmake are installed."
fi

# Sanity-check core tools we expect by now
for c in curl git tar cmake; do
    require_cmd "$c"
done

# -----------------------------
# SDKMAN + Java/sbt/Scala
# -----------------------------
if [[ ! -d "$HOME/.sdkman" ]]; then
    log "Installing SDKMAN."
    curl -fsSL --retry 5 --retry-delay 2 "https://get.sdkman.io" | bash
fi

log "Installing Java $JAVA_VERSION, sbt $SBT_VERSION, Scala $SCALA_VERSION via SDKMAN."
(
    # Disable nounset inside SDKMAN to avoid unbound var errors
    set +u

    # shellcheck disable=SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh"

    # Install if missing, then use (fail if we can't end up with the requested version)
    sdk install java "$JAVA_VERSION" >/dev/null 2>&1 || true
    sdk use java "$JAVA_VERSION" >/dev/null

    sdk install sbt "$SBT_VERSION" >/dev/null 2>&1 || true
    sdk use sbt "$SBT_VERSION" >/dev/null

    sdk install scala "$SCALA_VERSION" >/dev/null 2>&1 || true
    sdk use scala "$SCALA_VERSION" >/dev/null

    sdk default java "$JAVA_VERSION" >/dev/null || true
    sdk default sbt "$SBT_VERSION" >/dev/null || true
    sdk default scala "$SCALA_VERSION" >/dev/null || true
)

# Ensure user-local bin exists
mkdir -p "$INSTALL_PREFIX/bin"

# Persist PATH for interactive shells
if $UPDATE_PROFILE; then
    # Prefer $HOME-relative paths in shell profiles when possible
    INSTALL_PREFIX_FOR_PROFILE="$INSTALL_PREFIX"
    if [[ "$INSTALL_PREFIX_FOR_PROFILE" == "$HOME"* ]]; then
        INSTALL_PREFIX_FOR_PROFILE="\$HOME${INSTALL_PREFIX_FOR_PROFILE#"$HOME"}"
    fi
    append_if_missing "export PATH=\"$INSTALL_PREFIX_FOR_PROFILE/bin:\$PATH\"" "$PROFILE_FILE"
fi

# In CI, expose Java/sbt/scala without needing to source sdkman-init.sh
if $CI_MODE; then
    log "Configuring PATH shims for CI."
    ln -sf "$HOME/.sdkman/candidates/sbt/current/bin/sbt" "$INSTALL_PREFIX/bin/sbt"
    ln -sf "$HOME/.sdkman/candidates/scala/current/bin/scala" "$INSTALL_PREFIX/bin/scala"
    ln -sf "$HOME/.sdkman/candidates/scala/current/bin/scalac" "$INSTALL_PREFIX/bin/scalac"
    ln -sf "$HOME/.sdkman/candidates/java/current/bin/java" "$INSTALL_PREFIX/bin/java"
    ln -sf "$HOME/.sdkman/candidates/java/current/bin/javac" "$INSTALL_PREFIX/bin/javac"

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

# If a stale/incomplete directory exists, reinstall
if [[ -d "$OSS_CAD_DIR" && ! -f "$OSS_CAD_DIR/environment" ]]; then
    log "OSS CAD Suite directory exists but looks incomplete; reinstalling: $OSS_CAD_DIR"
    rm -rf "$OSS_CAD_DIR"
fi

if [[ ! -d "$OSS_CAD_DIR" ]]; then
    log "Installing OSS CAD Suite $OSS_CAD_SUITE_VERSION ($oss_arch)."
    curl -fL --retry 5 --retry-delay 2 --retry-all-errors \
        -o "$TMP_DIR/$OSS_CAD_SUITE_FILE" "$OSS_CAD_SUITE_URL"
    if [[ -n "$OSS_CAD_SUITE_SHA256" ]]; then
        echo "$OSS_CAD_SUITE_SHA256  $TMP_DIR/$OSS_CAD_SUITE_FILE" | sha256sum -c -
    fi
    tar -xzf "$TMP_DIR/$OSS_CAD_SUITE_FILE" -C "$TMP_DIR"
    # Archive extracts into $TMP_DIR/oss-cad-suite
    mv "$TMP_DIR/oss-cad-suite" "$OSS_CAD_DIR"
else
    log "OSS CAD Suite already present at $OSS_CAD_DIR"
fi

# Maintain a stable symlink
ln -sfn "$OSS_CAD_DIR" "$OSS_CAD_LINK"

# Persist environment setup for interactive shells
if $UPDATE_PROFILE; then
    append_if_missing "source \"$OSS_CAD_LINK/environment\"" "$PROFILE_FILE"
fi

# In CI, ensure binaries are on PATH; sourcing env is still recommended for tests
if $CI_MODE && [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "$OSS_CAD_LINK/bin" >>"$GITHUB_PATH"
fi

# -----------------------------
# Espresso (user-local install)
# -----------------------------
ESPRESSO_DIR="$HOME/espresso"

is_git_sha() {
    [[ "$1" =~ ^[0-9a-fA-F]{7,40}$ ]]
}

checkout_espresso_ref() {
    local ref="$1"

    # Fetch both branches and tags (shallow) to make tags work reliably
    git -C "$ESPRESSO_DIR" fetch --prune --tags --depth 1 origin || true

    if is_git_sha "$ref"; then
        # Ensure the commit is available locally
        git -C "$ESPRESSO_DIR" fetch --depth 1 origin "$ref" || true
        git -C "$ESPRESSO_DIR" checkout -q "$ref"
        return
    fi

    # Try: local branch/tag name first
    if git -C "$ESPRESSO_DIR" checkout -q "$ref" 2>/dev/null; then
        :
    # Try: remote branch
    elif git -C "$ESPRESSO_DIR" checkout -q "origin/$ref" 2>/dev/null; then
        :
    # Try: tag (detached)
    elif git -C "$ESPRESSO_DIR" checkout -q "tags/$ref" 2>/dev/null; then
        :
    else
        echo "Failed to checkout Espresso ref: $ref"
        exit 1
    fi

    # If it's a branch that exists on origin, hard-reset to it for determinism
    if git -C "$ESPRESSO_DIR" show-ref --verify --quiet "refs/remotes/origin/$ref"; then
        git -C "$ESPRESSO_DIR" reset --hard "origin/$ref"
    fi
}

if [[ ! -d "$ESPRESSO_DIR/.git" ]]; then
    log "Cloning Espresso ($ESPRESSO_REF)."
    if is_git_sha "$ESPRESSO_REF"; then
        git clone --depth 1 "$ESPRESSO_REPO" "$ESPRESSO_DIR"
    else
        # Try branch/tag clone first; fallback to default branch clone.
        git clone --depth 1 --branch "$ESPRESSO_REF" "$ESPRESSO_REPO" "$ESPRESSO_DIR" ||
            git clone --depth 1 "$ESPRESSO_REPO" "$ESPRESSO_DIR"
    fi
else
    log "Updating Espresso repository."
    git -C "$ESPRESSO_DIR" remote set-url origin "$ESPRESSO_REPO" || true
fi

checkout_espresso_ref "$ESPRESSO_REF"

log "Building and installing Espresso (Release) to $INSTALL_PREFIX ."
cmake -S "$ESPRESSO_DIR" -B "$ESPRESSO_DIR/build" \
    -DBUILD_DOC=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX"
cmake --build "$ESPRESSO_DIR/build" --parallel "$JOBS"
cmake --install "$ESPRESSO_DIR/build"

# Ensure INSTALL_PREFIX/bin is on PATH for the current shell if running interactively
if ! $CI_MODE; then
    case ":${PATH:=$PATH}:" in
    *":$INSTALL_PREFIX/bin:"*) : ;; # already present
    *) export PATH="$INSTALL_PREFIX/bin:$PATH" ;;
    esac
fi

log "Setup complete!"
if $UPDATE_PROFILE; then
    log "Notes:"
    log "- Open a new shell or run: source \"$PROFILE_FILE\" to load PATH/env updates."
fi
log "- In CI, PATH has been updated via GITHUB_PATH; OSS CAD env is typically sourced per step as needed."
