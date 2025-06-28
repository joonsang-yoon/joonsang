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
#   VERILATOR_REPO=https://github.com/verilator/verilator.git
#   VERILATOR_REF=stable|<tag>|<commit>
#   VERILATOR_DIR=$HOME/verilator
#   ESPRESSO_REPO=https://github.com/chipsalliance/espresso.git
#   ESPRESSO_REF=master|<tag>|<commit>
#   ESPRESSO_DIR=$HOME/espresso
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
  - Installs user-local tooling under ~/.local and ~/.sdkman.
  - Set NO_PROFILE=1 to avoid modifying shell rc files.
EOF
}

# ------------------------------
# Parse args / CI detection
# ------------------------------
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

# ------------------------------
# Configuration (overridable)
# ------------------------------
JAVA_VERSION="${JAVA_VERSION:-17.0.14-tem}"
SBT_VERSION="${SBT_VERSION:-1.10.11}"
SCALA_VERSION="${SCALA_VERSION:-2.13.16}"

VERILATOR_REPO="${VERILATOR_REPO:-https://github.com/verilator/verilator.git}"
VERILATOR_REF="${VERILATOR_REF:-stable}"
VERILATOR_DIR="${VERILATOR_DIR:-$HOME/verilator}"

ESPRESSO_REPO="${ESPRESSO_REPO:-https://github.com/chipsalliance/espresso.git}"
ESPRESSO_REF="${ESPRESSO_REF:-master}"
ESPRESSO_DIR="${ESPRESSO_DIR:-$HOME/espresso}"

INSTALL_PREFIX="${INSTALL_PREFIX:-$HOME/.local}" # user-local install prefix (no sudo)

# ------------------------------
# Helpers
# ------------------------------
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

is_git_sha() {
    [[ "$1" =~ ^[0-9a-fA-F]{7,40}$ ]]
}

checkout_git_ref() {
    local repo_dir="$1"
    local ref="$2"
    local repo_name="${3:-repository}"

    # Fetch tags and refresh the default remote state.
    git -C "$repo_dir" fetch --prune --tags --depth 1 origin || true

    if is_git_sha "$ref"; then
        # Ensure the commit is available locally.
        git -C "$repo_dir" fetch --depth 1 origin "$ref" || true
        git -C "$repo_dir" checkout -q "$ref"
        return
    fi

    # Prefer an explicit remote branch, as shallow clones often only know the default branch.
    if git -C "$repo_dir" ls-remote --exit-code --heads origin "$ref" >/dev/null 2>&1; then
        git -C "$repo_dir" fetch --depth 1 origin "$ref"
        git -C "$repo_dir" checkout -q -B "$ref" FETCH_HEAD
        return
    fi

    # Then try a fetched/local tag.
    if git -C "$repo_dir" rev-parse -q --verify "refs/tags/$ref" >/dev/null 2>&1; then
        git -C "$repo_dir" checkout -q "tags/$ref"
        return
    fi

    # Finally try any existing local ref.
    if git -C "$repo_dir" checkout -q "$ref" 2>/dev/null; then
        return
    fi

    echo "Failed to checkout ${repo_name} ref: $ref"
    exit 1
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

# ------------------------------
# OS detection
# ------------------------------
if [[ "$(uname -s)" != "Linux" ]]; then
    echo "This setup currently supports Linux only."
    exit 1
fi

# ------------------------------
# APT packages (Debian/Ubuntu)
# ------------------------------
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
            build-essential zip unzip curl git cmake python3 python3-pip \
            autoconf bison flex help2man perl libfl2 libfl-dev zlib1g zlib1g-dev ccache
    else
        log "No sudo and not root; skipping apt-get. Please ensure these are installed:"
        log "  build-essential zip unzip curl git cmake python3 python3-pip"
        log "  autoconf bison flex help2man perl libfl2 libfl-dev zlib1g zlib1g-dev ccache"
    fi
else
    log "Non-Debian-like OS detected; please ensure curl, git, cmake, autoconf, bison, flex, and perl are installed."
fi

# Sanity-check core tools we expect by now
for c in curl git cmake autoconf bison flex perl; do
    require_cmd "$c"
done

# ------------------------------
# SDKMAN + Java/SBT/Scala
# ------------------------------
if [[ ! -d "$HOME/.sdkman" ]]; then
    log "Installing SDKMAN."
    curl -fsSL --retry 5 --retry-delay 2 "https://get.sdkman.io" | bash
fi

log "Installing Java $JAVA_VERSION, SBT $SBT_VERSION, Scala $SCALA_VERSION via SDKMAN."
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

# In CI, expose Java/SBT/Scala without needing to source sdkman-init.sh
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

# ------------------------------
# Verilator (user-local install)
# ------------------------------
if [[ ! -d "$VERILATOR_DIR/.git" ]]; then
    log "Cloning Verilator ($VERILATOR_REF)."
    if is_git_sha "$VERILATOR_REF"; then
        git clone --depth 1 "$VERILATOR_REPO" "$VERILATOR_DIR"
    else
        # Try branch/tag clone first; fallback to default branch clone.
        git clone --depth 1 --branch "$VERILATOR_REF" "$VERILATOR_REPO" "$VERILATOR_DIR" ||
            git clone --depth 1 "$VERILATOR_REPO" "$VERILATOR_DIR"
    fi
else
    log "Updating Verilator repository."
    git -C "$VERILATOR_DIR" remote set-url origin "$VERILATOR_REPO" || true
fi

checkout_git_ref "$VERILATOR_DIR" "$VERILATOR_REF" "Verilator"

log "Building and installing Verilator to $INSTALL_PREFIX ."
(
    cd "$VERILATOR_DIR"
    unset VERILATOR_ROOT || true

    if [[ -f Makefile ]]; then
        make distclean >/dev/null 2>&1 || true
    fi

    autoconf
    ./configure --prefix="$INSTALL_PREFIX"
    make -j "$JOBS"
    make install
)

# ------------------------------
# Espresso (user-local install)
# ------------------------------
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

checkout_git_ref "$ESPRESSO_DIR" "$ESPRESSO_REF" "Espresso"

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
log "- In CI, PATH has been updated via GITHUB_PATH."
