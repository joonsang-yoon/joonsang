# Chisel Project Template

[![CI/CD](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/joonsang-yoon/joonsang)](https://github.com/joonsang-yoon/joonsang/blob/main/LICENSE)
[![Scala](https://img.shields.io/badge/Scala-2.13.16-DC322F.svg)](https://www.scala-lang.org/)
[![Chisel](https://img.shields.io/badge/Chisel-7.1.1-2A3172.svg)](https://www.chisel-lang.org/)

This project is a template for developing hardware designs using [Chisel](https://www.chisel-lang.org/), a Scala-based hardware description language. It uses the [Mill](https://mill-build.com/) build tool and is configured for compatibility with [Yosys](https://yosyshq.net/yosys/) and the [OSS CAD Suite](https://github.com/YosysHQ/oss-cad-suite-build).

## Features

- **Modern Chisel:** Leverages Chisel 7.1.1 and Scala 2.13.16 for hardware description.
- **Mill Build System:** Fast, type-safe, and reproducible builds.
- **Yosys Compatibility:** Pre-configured elaboration options for seamless integration with Yosys.
- **Floating Point Support:** Includes `HardFloat` for high-performance IEEE 754 operations.
- **Utility Library:** `HardUtils` provides essential hardware building blocks like reducers and counters.
- **CI/CD Ready:** Integrated GitHub Actions for automated testing and code formatting checks.
- **Automated Setup:** A comprehensive script to bootstrap your development environment on Linux.

## Quick Start

1. **Clone the repository:**
   ```bash
   git clone https://github.com/joonsang-yoon/joonsang.git
   cd joonsang
   ```

2. **Run the setup script:**
   ```bash
   bash ./scripts/setup.sh
   source ~/.bashrc
   ```

3. **Generate Verilog:**
   ```bash
   make verilog
   ```

4. **Run Tests:**
   ```bash
   make test
   ```

## Project Structure

The repository is organized into several Mill modules:

- **`TopLevelModule/`**: The primary module for your hardware designs.
  - `src/CustomDesign.scala`: Example Chisel module.
  - `src/Elaborate.scala`: Entry point for SystemVerilog generation with Yosys-optimized settings.
- **`ExternalModule/`**: Demonstrates multi-module project organization and dependencies.
- **`HardFloat/`**: An enhanced version built upon the original [Berkeley HardFloat](https://github.com/ucb-bar/berkeley-hardfloat). It provides a Chisel implementation of IEEE 754 floating-point units (addition, multiplication, division, square root, and conversions) using a specialized "re-coded" format for improved performance and area.
- **`HardUtils/`**: A collection of hardware utilities including bit manipulation (`LowMask`, `CountLeadingZeros`), high-performance reducers (`ReducersCarryChain`, `ReducersCarrySave`), and various counters and buffers.
- **`scripts/`**: Automation scripts, including the environment setup utility.
- **`generated/`**: Default output directory for Verilog and test artifacts (created at runtime).

## Prerequisites

The project requires the following tools:
- **Java JDK 17** (recommended)
- **Mill** (included as a wrapper script `./mill`)
- **OSS CAD Suite** (for Yosys compatibility and Verilog tools)
- **Espresso** (logic minimizer)

## Setup

You can use the provided setup script to install most dependencies on a Linux system:

```bash
bash ./scripts/setup.sh
```

This script will:
1. Install system dependencies via `apt-get` (if available).
2. Install SDKMAN and use it to install Java, sbt, and Scala.
3. Download and install the OSS CAD Suite.
4. Build and install Espresso.

After running the script, follow the instructions to update your `PATH` (e.g., `source ~/.bashrc`).

## Usage

The `Makefile` provides several targets for common development tasks:

### Generate SystemVerilog

To generate SystemVerilog for the default module (`TopLevelModule.CustomDesign`):

```bash
make verilog
```

To generate SystemVerilog for a specific module:

```bash
make verilog MODULE=ExternalModule.AnotherCustomDesign
```

To generate SystemVerilog for a parameterized module (e.g., from `HardFloat`):

```bash
make verilog MODULE="HardFloat.AddRecFN(8,24)"
```

The generated files will be located in the `generated/verilog/` directory.

### Run Tests

To run all tests, including those for the `HardFloat` module:

```bash
make test
```

**Note:** Running `HardFloat` tests will automatically initialize and build the `berkeley-softfloat-3` and `berkeley-testfloat-3` git submodules if they are not already present.

### Code Formatting

To reformat the Scala source code according to the `.scalafmt.conf` rules:

```bash
make reformat
```

To check if the code is correctly formatted:

```bash
make check-format
```

### Clean

To remove generated Verilog files and clean the `HardFloat` submodules:

```bash
make clean
```

## Documentation

Additional documentation for specific modules can be found in their respective directories:
- `HardUtils/docs/reference/LowMask.md`: Detailed explanation of the `LowMask` utility.
- `HardFloat/docs/research/digit_recurrence/`: Research papers and figures related to digit recurrence algorithms used in `HardFloat`.

## Elaboration Options

The `Elaborate` object in `TopLevelModule/src/Elaborate.scala` is configured with specific lowering options for Yosys compatibility:
- `disallowLocalVariables`: Prevents the use of automatic variables.
- `disallowPackedArrays`: Prevents the use of packed arrays.

It also uses `firtool` options to disable randomization and strip debug info for cleaner Verilog output.

## Troubleshooting

### Environment Variables
If you encounter issues with missing tools (like `yosys` or `espresso`), ensure that your environment is correctly set up. If you used `scripts/setup.sh`, you should source your shell profile:
```bash
source ~/.bashrc  # or ~/.zshrc
```
Additionally, the OSS CAD Suite environment can be sourced directly:
```bash
source ~/oss-cad-suite/environment
```

### Submodule Issues
If `HardFloat` tests fail due to submodule issues, you can try manually initializing them:
```bash
git submodule update --init --recursive
```

## Contributing

Contributions are welcome! If you have suggestions for improvements or new features, please feel free to:
1. **Open an Issue:** Discuss potential changes or report bugs.
2. **Submit a Pull Request:**
   - Fork the repository.
   - Create a new branch for your feature or bugfix.
   - Ensure your code follows the project's formatting rules (`make check-format`).
   - Verify your changes with tests (`make test`).
   - Submit your PR with a clear description of the changes.

## License

This project is licensed under the Apache License 2.0. See the `LICENSE` file for details.
