# Modular Chisel Starter with Mill

A Linux-first starter repo for Chisel projects. It gives you a working Mill build, a small Makefile wrapper, two tiny example modules, and a generic elaboration entrypoint so you can generate SystemVerilog from a fully qualified module name instead of writing one-off launchers for every block.

## What this snapshot gives you

- a working Chisel + Mill workspace with sensible defaults
- a simple cross-package example using `TopLevelModule` and `ExternalModule`
- `make` targets for elaboration, formatting, cleanup, and help
- `TopLevelModule/src/Elaborate.scala` for module-by-name elaboration
- `scripts/setup.sh` for installing the local Linux toolchain

## Repository layout

```text
.
├── TopLevelModule/
│   └── src/
│       ├── CustomDesign.scala
│       └── Elaborate.scala
├── ExternalModule/
│   └── src/
│       └── AnotherCustomDesign.scala
├── scripts/
│   └── setup.sh
├── .mill-version
├── .scalafmt.conf
├── Makefile
├── build.mill
├── mill
└── LICENSE
```

## Included example hardware

Two tiny modules are checked in so the project structure stays easy to follow:

- `ExternalModule.AnotherCustomDesign` increments an 8-bit input by 1.
- `TopLevelModule.CustomDesign` routes `a` through that leaf module, adds `b`, and registers the result to `c`.

The example is intentionally small, but it already exercises cross-module dependencies, elaboration, and RTL generation.

## Requirements

This snapshot targets Linux. Debian and Ubuntu are the smoothest path because `scripts/setup.sh` can install the required system packages with `apt-get` when they are missing.

The setup flow installs or configures:

- Java 17
- Scala 2.13.16
- SBT 1.10.11
- Verilator
- Espresso
- user-local tools under `~/.local` and `~/.sdkman`

## Quick start

```bash
bash ./scripts/setup.sh
source ~/.bashrc
make help
make verilog MODULE=TopLevelModule.CustomDesign
```

Generated SystemVerilog is written under:

```text
generated/verilog/<module path>/
```

For the default top level, that resolves to:

```text
generated/verilog/TopLevelModule/CustomDesign/
```

## Common commands

Show available targets:

```bash
make help
```

Generate the demo top level:

```bash
make verilog MODULE=TopLevelModule.CustomDesign
```

Generate the reusable leaf directly:

```bash
make verilog MODULE=ExternalModule.AnotherCustomDesign
```

Override the output directory:

```bash
make verilog MODULE=TopLevelModule.CustomDesign TARGET_DIR=./out_sv
```

Run the project test target:

```bash
make test
```

Reformat Scala sources:

```bash
make reformat
```

Check formatting without rewriting files:

```bash
make check-format
```

Remove generated RTL:

```bash
make clean
```

Remove generated RTL plus Mill output:

```bash
make distclean
```

## Elaborating your own modules

`make verilog` accepts a fully qualified module specification through `MODULE=...`.

The launcher supports:

- plain class names such as `MyPkg.MyModule`
- constructor-style specs such as `MyPkg.MyModule(32)`
- `Int` and `Boolean` constructor arguments
- fallback to companion `apply(...)` overloads when that is the cleaner API

The Makefile also turns the module spec into a filesystem-safe output path automatically. For example:

```bash
make verilog MODULE='TopLevelModule.MyParamModule(32)'
```

would write to something like:

```text
generated/verilog/TopLevelModule/MyParamModule_32/
```

If you later introduce another Mill module that is not reachable from `TopLevelModule`, set `PROJECT=<that module>` so the elaboration entrypoint has the right classpath.

## How `Elaborate.scala` works

`TopLevelModule/src/Elaborate.scala` wraps `circt.stage.ChiselStage` and adds a friendlier command-line interface around it.

In practice, that means you can elaborate modules by name, pass constructor arguments, and still forward normal ChiselStage options such as `--target-dir`.

To see the wrapper help together with the underlying ChiselStage help:

```bash
make elaborate-help
```

## Suggested next steps

Typical follow-on changes for this starter are:

- replace the demo modules with your own design hierarchy
- add Scala tests under the project test tree
- keep using the same `make verilog` flow as modules become parameterized
- extend `build.mill` module dependencies as the project grows

## Tool versions

- Scala: 2.13.16
- Chisel: 7.5.0
- Mill: 1.0.6
- Scalafmt: 3.8.5

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
