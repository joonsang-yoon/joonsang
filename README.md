# Chisel Project Template

This project is a template for developing hardware designs using [Chisel](https://www.chisel-lang.org/), a Scala-based hardware description language. It uses the [Mill](https://mill-build.com/) build tool and is configured for compatibility with [Yosys](https://yosyshq.net/yosys/) and the [OSS CAD Suite](https://github.com/YosysHQ/oss-cad-suite-build).

## Project Structure

- `TopLevelModule/`: Contains the main hardware design and elaboration logic.
  - `src/CustomDesign.scala`: An example Chisel module that performs a simple addition.
  - `src/Elaborate.scala`: The entry point for generating SystemVerilog from Chisel designs.
- `ExternalModule/`: A secondary module demonstrating how to organize and depend on multiple modules.
  - `src/AnotherCustomDesign.scala`: A simple module used by `TopLevelModule`.
- `scripts/setup.sh`: A comprehensive setup script for installing dependencies.
- `Makefile`: Provides convenient commands for common tasks.
- `build.mill.scala`: The Mill build configuration.

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

The generated files will be located in the `generated/verilog/` directory.

### Run Tests

To run all tests (if any are defined in `test` objects within `build.mill.scala`):

```bash
make test
```

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

To remove generated Verilog files:

```bash
make clean
```

## Elaboration Options

The `Elaborate` object in `TopLevelModule/src/Elaborate.scala` is configured with specific lowering options for Yosys compatibility:
- `disallowLocalVariables`: Prevents the use of automatic variables.
- `disallowPackedArrays`: Prevents the use of packed arrays.

It also uses `firtool` options to disable randomization and strip debug info for cleaner Verilog output.

## License

This project is licensed under the Apache License 2.0. See the `LICENSE` file for details.
