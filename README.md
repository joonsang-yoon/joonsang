# Chisel High-Performance Arithmetic Library

[![CI](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/joonsang-yoon/joonsang.svg)](LICENSE)
[![Scala](https://img.shields.io/badge/Scala-2.13.16-DC322F.svg?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![Chisel](https://img.shields.io/badge/Chisel-7.1.1-2A3172.svg)](https://www.chisel-lang.org/)

This project is a modular hardware design implemented in [Chisel](https://www.chisel-lang.org/) (Constructing Hardware in a Scala Embedded Language). It demonstrates a multi-module build structure using **Mill**, featuring a top-level module, an external component library, and comprehensive arithmetic libraries for both floating-point (**HardFloat**) and integer (**HardInt**) operations.

The project includes a robust build system, a custom elaboration script supporting parameterized module generation, and a setup script to configure the development environment.

## 📂 Project Structure

The project is organized into five main Chisel modules and dependencies:

*   **ExternalModule**: Contains reusable hardware components.
    *   `AnotherCustomDesign`: A module that adds 1 to an 8-bit input.
*   **TopLevelModule**: The main design entry point.
    *   `CustomDesign`: Instantiates components from other modules to demonstrate integration.
    *   `Elaborate`: A custom entry point for generating SystemVerilog using CIRCT.
*   **HardFloat**: A library of parameterized IEEE 754 floating-point arithmetic units.
    *   **Core Units**: Add/Sub, Compare, Conversions, and pipelined Booth-Dadda Multipliers/FMA with decoupled interfaces.
    *   **DivSqrtRecFN**: Implements Division and Square Root using digit recurrence algorithms (SRT).
    *   **Verification**: Includes a rigorous testing infrastructure using **Verilator** and **Berkeley TestFloat**.
    *   **Documentation**: Detailed research and derivation for the digit recurrence algorithms can be found in `HardFloat/docs/research`.
*   **HardInt**: A library of integer arithmetic units.
    *   **ALU**: A standard Arithmetic Logic Unit supporting basic RISC-V operations.
    *   **Radix4BoothMultiplier**: A high-performance multiplier using Radix-4 Booth encoding.
    *   **Radix4SRTDivider**: An integer divider implementing the Radix-4 SRT algorithm.
    *   **Verification**: Includes C++ test harnesses for exhaustive and randomized testing via Verilator.
*   **HardUtils**: A utility library providing low-level arithmetic building blocks.
    *   Contains compressors (3:2, 4:2), Wallace/Dadda reducers, pipeline buffers, and interconnect components (Crossbar Switch, Arbiters).
*   **rocket-chip**: Included as a submodule to provide utility classes (e.g., `DecodeLogic`) and standard constants.

```text
.
├── ExternalModule/           # Library module
│   └── src/                  # Source code (AnotherCustomDesign.scala)
├── HardFloat/                # Floating-point library
│   ├── src/                  # Chisel Source code
│   ├── test/                 # Scala tests and C++ Verilator harnesses
│   ├── berkeley-softfloat-3/ # Submodule: Reference software implementation
│   ├── berkeley-testfloat-3/ # Submodule: Test vector generation
│   └── docs/                 # Research documentation
├── HardInt/                  # Integer arithmetic library
│   ├── src/                  # Source code (ALU, Multiplier, Divider)
│   └── test/                 # Scala tests and C++ Verilator harnesses
├── HardUtils/                # Arithmetic utilities
│   └── src/                  # Source code (Counters, Reducers, Buffers, Interconnect)
├── TopLevelModule/           # Main module
│   └── src/                  # Source code (CustomDesign.scala, Elaborate.scala)
├── rocket-chip/              # Submodule: Rocket Chip generator library
├── .github/workflows/        # CI configurations
├── generated/                # Output directory for SystemVerilog and Test Artifacts
├── scripts/                  # Setup and utility scripts
├── build.mill.scala          # Mill build configuration
├── Makefile                  # Make shortcuts for common tasks
└── mill                      # Mill wrapper script
```

## 🚀 Getting Started

### Prerequisites

You can set up the entire environment (Java, Scala, sbt, Mill, OSS CAD Suite, and Espresso) using the provided setup script. This script is designed for Linux (Debian/Ubuntu recommended).

1.  **Run the setup script:**
    ```bash
    bash ./scripts/setup.sh
    ```
    *This will install dependencies to `~/.local` and `~/.sdkman`, and download the OSS CAD Suite.*

2.  **Source the environment:**
    After the script finishes, apply the changes to your current shell:
    ```bash
    source ~/.bashrc
    # Or manually source the CAD suite environment if needed:
    # source ~/oss-cad-suite/environment
    ```

### Manual Dependencies
If you prefer not to use the setup script, ensure you have:
*   **JDK 17+**
*   **Mill** (The included `./mill` wrapper handles this automatically)
*   **Make**
*   **Verilator** (Required for HardFloat and HardInt verification)
*   **Python 3** (for generating research plots in `HardFloat/docs`)
*   **Git Submodules**: This project relies on submodules for testing and dependencies. Initialize them via:
    ```bash
    git submodule update --init --recursive
    ```

## 🛠️ Usage

This project uses a `Makefile` to wrap common Mill commands for ease of use.

### List Available Commands

To view all available commands and their descriptions:
```bash
make help
```

### Generating SystemVerilog

To generate SystemVerilog for a specific module, use the `make verilog` command. You must specify the full class name of the module via the `MODULE` variable.

**Generate the Top Level Design:**
```bash
make verilog MODULE=TopLevelModule.CustomDesign
```

**Generate the External Module Design:**
```bash
make verilog MODULE=ExternalModule.AnotherCustomDesign
```

**Generate a Floating-Point Unit (e.g., Double Precision Adder):**
```bash
# Standard Double Precision: Exp=11, Sig=53
make verilog MODULE='HardFloat.AddRecFN(11, 53)'
```

**Generate an Integer Unit (e.g., Radix-4 SRT Divider):**
```bash
# 64-bit Divider, useMetadata=true, numXPRs=32
make verilog MODULE='HardInt.Radix4SRTDivider(64, true, 32)'
```

The generated files will be placed in:
`generated/verilog/<ModuleClass>/`

### Running Tests

To run all unit tests defined in the project, including the rigorous HardFloat and HardInt verification suites:
```bash
make test
```

**Verification Details:**
*   **HardFloat**: Tested against the industry-standard **Berkeley TestFloat** suite.
*   **HardInt**: Tested using custom C++ harnesses that perform exhaustive or large-scale random verification against software models.

### Formatting Code

To format the Scala source code using Scalafmt:
```bash
make reformat
```

To check if the code is properly formatted (useful for CI):
```bash
make check-format
```

*Note: The project now also includes `.clang-format` for C++ test harnesses and VS Code settings for C++, Python, and YAML formatting.*

### Cleaning Build Artifacts

To remove generated Verilog files and test artifacts:
```bash
make clean
```

To remove all build artifacts (including Mill cache and generated files):
```bash
make distclean
```
*This command also cleans the HardFloat submodule build directories.*

## ⚙️ Advanced Elaboration

The project includes a custom `Elaborate` object (`TopLevelModule/src/Elaborate.scala`) that wraps the `ChiselStage`. It provides several advanced features:

1.  **Reflection-based Instantiation**: It can instantiate modules by string name.
2.  **Parameter Parsing**: It supports passing constructor arguments via the command-line string.
3.  **FIRTOOL Options**: It automatically applies options to strip debug info and disable randomization for cleaner Verilog output compatible with Yosys.

## 🔄 Continuous Integration

This project utilizes GitHub Actions for Continuous Integration. The workflow is defined in `.github/workflows/ci.yml` and performs the following on every push and pull request to `main`:
*   Sets up the environment (Java, Scala, OSS CAD Suite).
*   Generates Verilog for the top-level design.
*   Runs the full test suite (including HardFloat and HardInt verification).
*   Uploads generated Verilog and test artifacts as build artifacts.

## 📦 Dependencies

*   **Scala**: 2.13.16
*   **Chisel**: 7.1.1
*   **Mill**: 1.0.6 (via wrapper)
*   **Verilator**: (System dependency for testing)
*   **Rocket Chip**: (Included as submodule)
*   **Berkeley SoftFloat/TestFloat**: (Included as submodules)

## 📄 License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
