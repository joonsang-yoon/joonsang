# Chisel Arithmetic Library

This snapshot turns the starter project into a floating-point arithmetic workspace centered on `HardFloat`. The lightweight demo modules and generic elaboration launcher are still present, but the main value is now the checked-in floating-point RTL, the supporting `HardUtils` library, the Berkeley-backed verification flow, and the research material that explains part of the implementation.

## What is in this snapshot

- `HardFloat` for floating-point arithmetic, conversion, compare, divide, and square root
- `HardUtils` for reusable arithmetic helpers such as masks, reducers, counters, and buffers
- Berkeley SoftFloat/TestFloat submodules for reference vectors and validation utilities
- digit-recurrence research notes, figures, and Python plot generators
- CI, formatting, and editor configuration for day-to-day development

## Repository layout

```text
.
├── TopLevelModule/
│   └── src/
├── ExternalModule/
│   └── src/
├── HardFloat/
│   ├── src/
│   ├── test/
│   ├── berkeley-softfloat-3/   # git submodule
│   ├── berkeley-testfloat-3/   # git submodule
│   └── docs/
│       └── research/
├── HardUtils/
│   ├── src/
│   └── docs/
│       └── reference/
├── .github/workflows/
├── scripts/
├── THIRD_PARTY_NOTICES.md
├── Makefile
├── build.mill
└── mill
```

## Core libraries

### HardFloat

`HardFloat` is the main library in this revision. The source tree includes modules such as:

- `AddRecFN`
- `MulRecFN`
- `MulAddRecFN`
- `CompareRecFN`
- `DivSqrtRecFN`
- `INToRecFN`
- `RecFNToIN`
- `RecFNToRecFN`
- `RoundAnyRawFNToRecFN`

The naming follows Berkeley HardFloat conventions, so most datapath-facing modules work with **recoded floating-point values (`RecFN`)** internally.

Common width presets used throughout the code and tests are:

- half precision: `(5, 11)`
- single precision: `(8, 24)`
- double precision: `(11, 53)`

### HardUtils

`HardUtils` collects small arithmetic-support blocks that are useful well beyond floating-point code. Included helpers cover:

- `CountLeadingZeros`
- `LowMask`
- `OrReduceBy2` and `OrReduceBy4`
- pipeline and skid buffers
- 2:2, 3:2, 4:3, and 5:3 counters
- Wallace and Dadda reduction helpers
- concatenation-order helpers

Reference notes for selected helpers live under `HardUtils/docs/reference/`, including [`LowMask`](HardUtils/docs/reference/LowMask.md).

### Demo modules and launcher

`TopLevelModule` and `ExternalModule` remain in place as small examples, and `TopLevelModule/src/Elaborate.scala` is still the entrypoint behind `make verilog`.

That means the same launcher can elaborate both the demo modules and parameterized `HardFloat` blocks from the command line.

## Toolchain and submodules

This repository targets Linux. The setup script installs the user-local toolchain under `~/.local` and `~/.sdkman`:

- Java 17
- Scala 2.13.16
- SBT 1.10.11
- Verilator
- Espresso

Before running the floating-point verification flow on a fresh checkout, initialize the Berkeley submodules:

```bash
make submodules
```

## Quick start

```bash
bash ./scripts/setup.sh
source ~/.bashrc
make submodules
make help
```

Generate the demo top level:

```bash
make verilog MODULE=TopLevelModule.CustomDesign
```

Generate a double-precision floating-point adder:

```bash
make verilog MODULE='HardFloat.AddRecFN(11, 53)'
```

Generate the divide/square-root core for single precision:

```bash
make verilog MODULE='HardFloat.DivSqrtRecFN(8, 24)'
```

Generated SystemVerilog is written under:

```text
generated/verilog/<module path>/
```

For example:

```text
HardFloat.AddRecFN(11, 53)
→ generated/verilog/HardFloat/AddRecFN_11_53/
```

For the full launcher help, including ChiselStage options:

```bash
make elaborate-help
```

## Testing and verification

Run the repo-level test entrypoint:

```bash
make test
```

Run only the HardFloat regression suite:

```bash
make test-hardfloat
```

The HardFloat flow:

1. initializes Berkeley SoftFloat and TestFloat when needed
2. builds the external vector generators used by the tests
3. emits SystemVerilog into `generated/test_artifacts/HardFloat/...`
4. compiles the DUT and C++ harnesses with Verilator
5. drives Berkeley-generated vectors into the design under test

`HardFloat/Makefile` can also be used directly if you want to work inside the floating-point tree without going through the repo-root target.

Set `VCD=1` before running tests if you want Verilator waveform tracing.

## Documentation and developer tooling

Under `HardFloat/docs/research/digit_recurrence/` you will find:

- the rendered PDF write-up
- the LaTeX source for the document
- Python scripts for generating radix-2 and radix-4 selector plots
- notes for the overlap-resolution transform used by the optimized tables

This snapshot also ships with:

- `.clang-format` for the C/C++ harnesses
- VS Code settings for C, C++, Python, and YAML
- a GitHub Actions workflow in `.github/workflows/ci.yml`

## Development workflow

Reformat Scala sources:

```bash
make reformat
```

Check formatting without rewriting files:

```bash
make check-format
```

## Tool versions

- Scala: 2.13.16
- Chisel: 7.5.0
- Mill: 1.0.6
- Scalafmt: 3.8.5

## License and third-party code

Unless otherwise noted, the repository is licensed under Apache License 2.0.

Third-party code and submodules are called out separately in `THIRD_PARTY_NOTICES.md` and the license files under `HardFloat/`.
