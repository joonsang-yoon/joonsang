#!/bin/bash
set -euo pipefail

# Tool versions
JAVA_VERSION="17.0.14-tem"
SBT_VERSION="1.10.11"
SCALA_VERSION="2.13.16"
OSS_CAD_SUITE_VERSION="2025-07-09"
OSS_CAD_SUITE_VERSION_COMPACT="${OSS_CAD_SUITE_VERSION//-/}"
OSS_CAD_SUITE_FILE="oss-cad-suite-linux-x64-${OSS_CAD_SUITE_VERSION_COMPACT}.tgz"
OSS_CAD_SUITE_URL="https://github.com/YosysHQ/oss-cad-suite-build/releases/download/${OSS_CAD_SUITE_VERSION}/${OSS_CAD_SUITE_FILE}"
ESPRESSO_DIR="$HOME/espresso"
INITIAL_DIR=$(pwd)

# System dependencies
echo "Installing system dependencies..."
sudo apt update && sudo apt upgrade -y
sudo apt install -y build-essential zip unzip curl git wget cmake

# SDKMAN and Java/Scala tools
if [ ! -d "$HOME/.sdkman" ]; then
    echo "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash
fi

echo "Installing Java, sbt, and Scala..."
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java "$JAVA_VERSION"
sdk install sbt "$SBT_VERSION"
sdk install scala "$SCALA_VERSION"
set -u

# OSS CAD Suite
if [ ! -d "$HOME/oss-cad-suite" ]; then
    echo "Installing OSS CAD Suite..."
    cd "$HOME"
    wget "$OSS_CAD_SUITE_URL"
    tar -xzf "$OSS_CAD_SUITE_FILE"
    rm "$OSS_CAD_SUITE_FILE"

    if ! grep -q "source $HOME/oss-cad-suite/environment" "$HOME/.bashrc"; then
        echo "source $HOME/oss-cad-suite/environment" >>"$HOME/.bashrc"
    fi
    source "$HOME/oss-cad-suite/environment" || true
else
    echo "OSS CAD Suite already installed."
fi

# Espresso
if [ ! -d "$ESPRESSO_DIR" ]; then
    echo "Installing Espresso..."
    git clone https://github.com/chipsalliance/espresso.git "$ESPRESSO_DIR"
    cd "$ESPRESSO_DIR"
    mkdir -p build && cd build
    cmake .. -DBUILD_DOC=OFF -DCMAKE_INSTALL_PREFIX=/usr/local
    make -j"$(nproc)"
    sudo make install
else
    echo "Espresso already installed."
fi

# Return to original directory
cd "$INITIAL_DIR"
echo "Setup complete!"
