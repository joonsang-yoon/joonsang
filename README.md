# Chisel SoC Framework

[![CI/CD](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/joonsang-yoon/joonsang/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/joonsang-yoon/joonsang)](https://github.com/joonsang-yoon/joonsang/blob/main/LICENSE)
[![Scala](https://img.shields.io/badge/Scala-2.13.16-DC322F.svg)](https://www.scala-lang.org/)
[![Chisel](https://img.shields.io/badge/Chisel-7.1.1-2A3172.svg)](https://www.chisel-lang.org/)

This is a template repository for creating a System-on-a-Chip (SoC) using the [Chisel](https://www.chisel-lang.org/) hardware description language. It provides a basic project structure, build system, and a set of common commands to get you started with your own Chisel-based SoC design.

## Table of Contents

- [Chisel SoC Framework](#chisel-soc-framework)
  - [Table of Contents](#table-of-contents)
  - [Project Structure](#project-structure)
  - [Getting Started](#getting-started)
    - [Prerequisites](#prerequisites)
    - [Installation](#installation)
  - [Usage](#usage)
    - [Available Commands](#available-commands)
    - [Elaborate Options](#elaborate-options)
  - [Available Modules](#available-modules)
    - [TopLevelModule.CustomDesign](#toplevelmodulecustomdesign)
    - [ExternalModule.AnotherCustomDesign](#externalmoduleanothercustomdesign)
    - [HardFloat](#hardfloat)
  - [Contributing](#contributing)
  - [License](#license)

## Project Structure

The project is organized into the following directory structure:

```
.
├── build.mill.scala
├── ExternalModule
│   └── src
│       └── AnotherCustomDesign.scala
├── HardFloat
│   ├── src
│   │   ├── AddRecFN.scala
│   │   ├── CompareRecFN.scala
│   │   ├── DivSqrtRecF64.scala
│   │   ├── DivSqrtRecF64_mulAddZ31.scala
│   │   ├── DivSqrtRecFN_small.scala
│   │   ├── HardFloatCore.scala
│   │   ├── INToRecFN.scala
│   │   ├── MulAddRecFN.scala
│   │   ├── MulRecFN.scala
│   │   ├── RecFNToIN.scala
│   │   ├── RecFNToRecFN.scala
│   │   └── RoundAnyRawFNToRecFN.scala
│   └── test
│       └── src
│           ├── HardFloatTester.scala
│           ├── package.scala
│           ├── ValExec_AddRecFN.scala
│           ├── ValExec_CompareRecFN.scala
│           ├── ValExec_DivSqrtRecF64.scala
│           ├── ValExec_DivSqrtRecFN_small.scala
│           ├── ValExec_FNFromRecFN.scala
│           ├── ValExec_INToRecFN.scala
│           ├── ValExec_MulAddRecFN.scala
│           ├── ValExec_MulRecFN.scala
│           ├── ValExec_RecFNToIN.scala
│           └── ValExec_RecFNToRecFN.scala
├── HardUtils
│   └── src
│       └── BitUtils.scala
├── LICENSE
├── Makefile
├── mill
├── README.md
├── scripts
│   └── setup.sh
└── TopLevelModule
    └── src
        ├── CustomDesign.scala
        └── Elaborate.scala
```

-   `build.mill.scala`: The build script for the [Mill](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html) build tool.
-   `TopLevelModule`: A Chisel module that serves as the top-level of the SoC design.
-   `ExternalModule`: A Chisel module that is a dependency of `TopLevelModule`.
-   `HardFloat`: A Chisel module that implements floating-point arithmetic.
-   `HardUtils`: A Chisel module that provides utility functions.
-   `LICENSE`: The license file for the project.
-   `Makefile`: A set of common commands for building, testing, and cleaning the project.
-   `mill`: The Mill build tool executable.
-   `README.md`: This file.
-   `scripts`: A directory containing useful scripts for the project.

## Getting Started

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

### Prerequisites

This project requires the following dependencies to be installed on your system:

*   Java 17
*   sbt
*   Scala
*   OSS CAD Suite
*   Espresso
*   build-essential
*   zip
*   unzip
*   curl
*   git
*   wget
*   cmake

### Installation

The `scripts/setup.sh` script is provided to automate the installation of all the necessary dependencies.

To run the script, execute the following command from the root of the project:

```bash
bash ./scripts/setup.sh
```

## Usage

This project uses a `Makefile` to provide a set of commands for common tasks.

### Available Commands

| Command | Description |
| --- | --- |
| `make verilog` | Generate SystemVerilog for a specified module. You must specify the module to be elaborated using the `MODULE` variable. The default is `TopLevelModule.CustomDesign`. |
| `make test` | Run all the tests in the project. |
| `make reformat` | Reformat all the source files. |
| `make check-format` | Check the formatting of all the source files without modifying them. |
| `make clean` | Remove all the generated files and directories. |
| `make help` | Display a list of all the available commands and their descriptions. |

### Elaborate Options

The `Elaborate` object provides the following options for generating SystemVerilog:

```
--module-name <value>   The name of the module to elaborate
--target-dir <value>    The directory where the generated files will be placed
```

## Available Modules

This project provides the following modules as examples:

### TopLevelModule.CustomDesign

This module computes the sum of two 8-bit inputs and outputs the result. It also instantiates the `AnotherCustomDesign` module.

### ExternalModule.AnotherCustomDesign

This module adds 1 to the input.

### HardFloat

This module provides a set of floating-point units that can be used in your own designs. The following floating-point units are available:

*   `AddRecFN`
*   `CompareRecFN`
*   `DivSqrtRecF64`
*   `DivSqrtRecFN_small`
*   `INToRecFN`
*   `MulAddRecFN`
*   `MulRecFN`
*   `RecFNToIN`
*   `RecFNToRecFN`
*   `RoundAnyRawFNToRecFN`

## Contributing

Contributions are welcome! Please feel free to submit a pull request or open an issue on the GitHub repository.

## License

This project is licensed under the terms of the LICENSE file.
