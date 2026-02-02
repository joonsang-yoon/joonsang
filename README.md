# Chisel High-Performance Arithmetic Library

[![CI](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/joonsang-yoon/joonsang.svg)](LICENSE)
[![Scala](https://img.shields.io/badge/Scala-2.13.16-DC322F.svg?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![Chisel](https://img.shields.io/badge/Chisel-7.5.0-2A3172.svg)](https://www.chisel-lang.org/)

This project is a modular hardware design implemented in [Chisel](https://www.chisel-lang.org/) (Constructing Hardware in a Scala Embedded Language). It demonstrates a multi-module build structure using **Mill**, featuring a top-level module, an external component library, and comprehensive arithmetic libraries for both floating-point (**HardFloat**) and integer (**HardInt**) operations.

The project includes a robust build system, a custom elaboration script supporting parameterized module generation, and a setup script to configure the development environment.

## 📂 Project Structure

The project is organized into five main Chisel modules and dependencies:

*   **TopLevelModule**: The main design entry point.
    *   `CustomDesign`: Instantiates components from other modules to demonstrate integration.
    *   `Elaborate`: A custom entry point for generating SystemVerilog using CIRCT.
*   **ExternalModule**: Contains reusable hardware components.
    *   `AnotherCustomDesign`: A module that adds 1 to an 8-bit input.
*   **HardFloat**: A library of parameterized IEEE 754 floating-point arithmetic units.
    *   **Core Units**: Add/Sub, Multiply, Fused Multiply-Add (FMA), Compare, and Conversions.
    *   **DivSqrtRecFN**: Implements Division and Square Root using digit recurrence algorithms (SRT).
    *   **Verification**: Includes a rigorous testing infrastructure using **Verilator** and **Berkeley TestFloat**.
    *   **Documentation**: Detailed research, LaTeX derivations, and Python scripts for generating digit recurrence plots (including overlap transformation logic) can be found in `HardFloat/docs/research`.
*   **HardInt**: A library of integer arithmetic units.
    *   **ALU**: A standard Arithmetic Logic Unit supporting basic RISC-V operations.
    *   **Radix4BoothMultiplier**: A high-performance multiplier using Radix-4 Booth encoding and Dadda reduction. Includes a `RISCVMultiplier` wrapper with tag routing.
    *   **Radix4SRTDivider**: An integer divider implementing the Radix-4 SRT algorithm. Includes a `RISCVDivider` wrapper with tag routing.
    *   **Verification**: Includes C++ test harnesses for exhaustive verification via Verilator (e.g., exhaustive 15-bit and 16-bit multiplier/divider tests).
*   **HardUtils**: A utility library providing low-level arithmetic building blocks.
    *   **BitUtils**: Bit-level manipulation utilities like `CountLeadingZeros`, `LowMask`, `OrReduceBy2`, and `OrReduceBy4`.
    *   **Counters & Reducers**: Contains counters/compressors (2:2, 3:2, 4:3, 5:3) and Wallace/Dadda reducers (both Carry-Save and Carry-Chain variants) with customizable concatenation ordering (`ConcatOrder`).
    *   **Buffers**: Pipeline buffers and skid buffers, including iterative variants (`IterativePipeBuffer`, `IterativeSkidBuffer`) for multi-cycle operations.
    *   **Documentation**: Reference documentation for utilities (e.g., `LowMask`) can be found in `HardUtils/docs/reference`.
*   **rocket-chip**: Included as a submodule to provide utility classes (e.g., `DecodeLogic`) and standard constants.

```text
.
├── TopLevelModule/           # Main module
│   └── src/                  # Source code (CustomDesign.scala, Elaborate.scala)
├── ExternalModule/           # Library module
│   └── src/                  # Source code (AnotherCustomDesign.scala)
├── HardFloat/                # Floating-point library
│   ├── src/                  # Chisel Source code
│   ├── test/                 # Scala tests and C++ Verilator harnesses
│   ├── berkeley-softfloat-3/ # Submodule: Reference software implementation
│   ├── berkeley-testfloat-3/ # Submodule: Test vector generation
│   └── docs/                 # Research documentation & Python plot scripts
├── HardInt/                  # Integer arithmetic library
│   ├── src/                  # Source code (ALU, Multiplier, Divider)
│   └── test/                 # Scala tests and C++ Verilator harnesses
├── HardUtils/                # Arithmetic utilities
│   ├── src/                  # Source code (BitUtils, Counters, Reducers, Buffers)
│   └── docs/                 # Utility reference documentation
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
    *This will install dependencies to `~/.local` and `~/.sdkman`, download the OSS CAD Suite, and build the Espresso logic minimizer.*

2.  **Source the environment:**
    After the script finishes, apply the changes to your current shell:
    ```bash
    source ~/.bashrc
    # Or manually source the CAD suite environment if needed:
    # source ~/oss-cad-suite/environment
    ```

### Manual Dependencies
If you prefer not to use the setup script, ensure you have:
*   **Make**
*   **JDK 17+**
*   **Mill** (The included `./mill` wrapper handles this automatically)
*   **Git Submodules**: This project relies on submodules for testing and dependencies. Initialize them via the Makefile:
    ```bash
    make submodules
    ```
*   **Verilator** (Required for HardFloat and HardInt verification)
*   **Espresso Logic Minimizer** (Required for generating optimized SRT digit selection tables via Chisel's `EspressoMinimizer`)
*   **Python 3** (for generating research plots in `HardFloat/docs`)

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
# 64-bit Divider
make verilog MODULE='HardInt.Radix4SRTDivider(64)'
```

**Customizing the Output Directory:**
By default, generated files are placed in `generated/verilog/<ModulePath>/`. You can override this by specifying `TARGET_DIR`:
```bash
make verilog MODULE=TopLevelModule.CustomDesign TARGET_DIR=./my_custom_dir
```

### Running Tests

To run all unit tests defined in the project, including the rigorous HardFloat and HardInt verification suites:
```bash
make test
```

To run *only* the HardFloat verification suite:
```bash
make test-hardfloat
```

To run *only* the HardInt verification suite:
```bash
make test-hardint
```

**Verification Details:**
*   **HardFloat**: Tested against the industry-standard **Berkeley TestFloat** suite.
*   **HardInt**: Tested using custom C++ harnesses that perform exhaustive verification against software models.

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

To see the full list of supported `ChiselStage` options, `firtool` defaults, and supported constructor argument types, you can run:
```bash
make elaborate-help
```

## 🔄 Continuous Integration

This project utilizes GitHub Actions for Continuous Integration. The workflow is defined in `.github/workflows/ci.yml` and performs the following on every push and pull request to `main`:
*   Sets up the environment (Java, Scala, OSS CAD Suite, and Espresso).
*   Checks code formatting.
*   Generates Verilog for the top-level design.
*   Runs the full test suite (including HardFloat and HardInt verification).
*   Uploads generated Verilog and test artifacts as build artifacts.

## 📦 Dependencies

*   **Scala**: 2.13.16
*   **Chisel**: 7.5.0
*   **Mill**: 1.0.6 (via wrapper)
*   **Verilator**: (System dependency for testing)
*   **Espresso**: (System dependency for logic minimization)
*   **Rocket Chip**: (Included as submodule)
*   **Berkeley SoftFloat/TestFloat**: (Included as submodules)

## 📄 License

Unless otherwise noted, this project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file.

### Components under different licenses

This project contains third-party components that are licensed separately:

- **`HardFloat/`**: contains code derived from **Berkeley HardFloat** and is licensed under the Berkeley HardFloat license (BSD 3‑Clause–style; Regents of the University of California). See `HardFloat/LICENSE`.
- **`HardFloat/berkeley-softfloat-3/`** (git submodule): licensed separately; see `HardFloat/berkeley-softfloat-3/COPYING.txt`.
- **`HardFloat/berkeley-testfloat-3/`** (git submodule): licensed separately; see `HardFloat/berkeley-testfloat-3/COPYING.txt`.
- **`rocket-chip/`** (git submodule): licensed separately; see the license file(s) under `rocket-chip/` (e.g., `LICENSE.Berkeley`, `LICENSE.SiFive`, `LICENSE.jtag`).

For a consolidated component list and attributions, see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
