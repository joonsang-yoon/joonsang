# Modular Chisel Project Template with Mill

This project is a modular hardware design implemented in [Chisel](https://www.chisel-lang.org/) (Constructing Hardware in a Scala Embedded Language). It demonstrates a multi-module build structure using **Mill**, including a top-level module that instantiates a component from an external module.

The project includes a robust build system, a custom elaboration script supporting parameterized module generation, and a setup script to configure the development environment.

## 📂 Project Structure

The project is divided into two main Chisel modules:

*   **TopLevelModule**: The main design entry point.
    *   `CustomDesign`: Takes two inputs (`a`, `b`), instantiates `AnotherCustomDesign` to process `a`, adds `b` to the result, and registers the output.
    *   `Elaborate`: A custom entry point for generating SystemVerilog using CIRCT.
*   **ExternalModule**: Contains reusable hardware components.
    *   `AnotherCustomDesign`: A module that adds 1 to an 8-bit input.

```text
.
├── TopLevelModule/       # Main module
│   └── src/              # Source code (CustomDesign.scala, Elaborate.scala)
├── ExternalModule/       # Library module
│   └── src/              # Source code (AnotherCustomDesign.scala)
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

**Generate a Parameterized Module:**
You can pass constructor arguments directly in the `MODULE` string. Be sure to quote the string to prevent shell evaluation of the parentheses. The Makefile will automatically format the output directory name (e.g., `generated/verilog/TopLevelModule/MyParamModule_32`):
```bash
make verilog MODULE='TopLevelModule.MyParamModule(32)'
```

**Customizing the Output Directory:**
By default, generated files are placed in `generated/verilog/<ModulePath>/`. You can override this by specifying `TARGET_DIR`:
```bash
make verilog MODULE=TopLevelModule.CustomDesign TARGET_DIR=./my_custom_dir
```

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

To see the full list of supported `ChiselStage` options, `firtool` defaults, and supported constructor argument types, you can run:
```bash
make elaborate-help
```

## 📦 Dependencies

*   **Scala**: 2.13.16
*   **Chisel**: 7.5.0
*   **Mill**: 1.0.6 (via wrapper)

## 📄 License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file.
