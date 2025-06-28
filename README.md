# Chisel High-Performance Arithmetic Library

This project is a modular hardware design implemented in [Chisel](https://www.chisel-lang.org/) (Constructing Hardware in a Scala Embedded Language). It demonstrates a multi-module build structure using **Mill**, featuring a top-level module, an external component library, and a comprehensive floating-point arithmetic library (**HardFloat**).

The project includes a robust build system, a custom elaboration script supporting parameterized module generation, and a setup script to configure the development environment.

## 📂 Project Structure

The project is organized into four main Chisel modules:

*   **ExternalModule**: Contains reusable hardware components.
    *   `AnotherCustomDesign`: A module that adds 1 to an 8-bit input.
*   **TopLevelModule**: The main design entry point.
    *   `CustomDesign`: Instantiates components from other modules to demonstrate integration.
    *   `Elaborate`: A custom entry point for generating SystemVerilog using CIRCT.
*   **HardFloat**: A library of parameterized IEEE 754 floating-point arithmetic units.
    *   Includes Add/Sub, Multiply, Fused Multiply-Add (FMA), Compare, and Conversions.
    *   **DivSqrtRecFN**: Implements Division and Square Root using digit recurrence algorithms (SRT).
    *   **Documentation**: Detailed research and derivation for the digit recurrence algorithms can be found in `HardFloat/docs/research`.
*   **HardUtils**: A utility library providing low-level arithmetic building blocks.
    *   Contains compressors (3:2, 4:2), Wallace/Dadda reducers, and pipeline buffers.

```text
.
├── ExternalModule/       # Library module
│   └── src/              # Source code (AnotherCustomDesign.scala)
├── HardFloat/            # Floating-point library
│   ├── src/              # Source code (AddRecFN, DivSqrtRecFN, etc.)
│   └── docs/             # Research documentation (SRT Division/Sqrt theory)
├── HardUtils/            # Arithmetic utilities
│   └── src/              # Source code (Counters, Reducers, Buffers)
├── TopLevelModule/       # Main module
│   └── src/              # Source code (CustomDesign.scala, Elaborate.scala)
├── generated/            # Output directory for SystemVerilog
├── scripts/              # Setup and utility scripts
├── build.mill.scala      # Mill build configuration
├── Makefile              # Make shortcuts for common tasks
└── mill                  # Mill wrapper script
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
The `HardFloat` modules are parameterized. You can pass parameters (Exponent Width, Significand Width) directly in the command string.
```bash
# Standard Double Precision: Exp=11, Sig=53
make verilog MODULE='HardFloat.AddRecFN(11, 53)'
```

The generated files will be placed in:
`generated/verilog/<ModuleClass>/`

### Running Tests

To run all unit tests defined in the project:
```bash
make test
```

### Formatting Code

To format the Scala source code using Scalafmt:
```bash
make reformat
```

To check if the code is properly formatted (useful for CI):
```bash
make check-format
```

### Cleaning Build Artifacts

To remove generated Verilog files:
```bash
make clean
```

To remove all build artifacts (including Mill cache and generated files):
```bash
make distclean
```

## ⚙️ Advanced Elaboration

The project includes a custom `Elaborate` object (`TopLevelModule/src/Elaborate.scala`) that wraps the `ChiselStage`. It provides several advanced features:

1.  **Reflection-based Instantiation**: It can instantiate modules by string name.
2.  **Parameter Parsing**: It supports passing constructor arguments via the command-line string.
3.  **FIRTOOL Options**: It automatically applies options to strip debug info and disable randomization for cleaner Verilog output compatible with Yosys.

## 📦 Dependencies

*   **Scala**: 2.13.16
*   **Chisel**: 7.1.1
*   **Chisel Plugin**: 7.1.1
*   **Mill**: 1.0.6 (via wrapper)

## 📄 License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
