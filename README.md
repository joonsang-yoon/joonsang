# Chisel Arithmetic Library

This snapshot broadens the repository from a floating-point-focused tree into a mixed floating-point and integer arithmetic workspace. `HardFloat` remains central, `HardInt` adds reusable integer execution units, `HardUtils` stays the shared implementation layer, and a `rocket-chip` submodule provides the decode/constants support used by the RISC-V-oriented wrappers on the integer side.

## What changed in this snapshot

- `HardFloat` still covers floating-point arithmetic, conversion, compare, divide, and square root
- `HardInt` now adds ALU, Booth multiplication, and radix-4 SRT division blocks
- `HardUtils` remains the shared home for masks, buffers, reducers, and final-adder helpers
- `rocket-chip` is vendored as a library dependency for decode helpers and scalar-op constants
- verification now has separate floating-point and integer flows

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
├── HardInt/
│   ├── src/
│   └── test/
├── HardUtils/
│   ├── src/
│   └── docs/
├── rocket-chip/                # git submodule
├── .github/workflows/
├── scripts/
├── THIRD_PARTY_NOTICES.md
├── Makefile
├── build.mill
└── mill
```

## Main libraries

### HardFloat

The floating-point side includes modules such as:

- `AddRecFN`
- `MulRecFN`
- `MulAddRecFN`
- `CompareRecFN`
- `DivSqrtRecFN`
- `INToRecFN`
- `RecFNToIN`
- `RecFNToRecFN`
- `RoundAnyRawFNToRecFN`

These modules follow Berkeley HardFloat conventions and mostly use recoded floating-point (`RecFN`) interfaces internally.

Common width presets are:

- half precision: `(5, 11)`
- single precision: `(8, 24)`
- double precision: `(11, 53)`

### HardInt

`HardInt` is new in this revision and provides reusable integer execution blocks. Included RTL modules are:

- `ALU(dataWidth)` for arithmetic, logic, shifts, and compares
- `Radix4BoothMultiplier(dataWidth, initHeight)` for Booth-based multiplication
- `RISCVMultiplier(...)` as a request/response wrapper around the multiplier core
- `Radix4SRTDivider(dataWidth)` for integer division and remainder generation
- `RISCVDivider(...)` as a request/response wrapper around the divider core

The shipped integer tests use Verilator harnesses that run exhaustive all-input checks for 15-bit and 16-bit configurations.

### HardUtils

`HardUtils` stays the shared support layer between the floating-point and integer libraries. Included helpers cover:

- `CountLeadingZeros`, `LowMask`, `OrReduceBy2`, and `OrReduceBy4`
- compressor primitives and reduction-tree helpers
- pipeline, skid, and iterative skid buffers
- `FinalAdder`
- ordering helpers such as `ConcatOrder`

### `rocket-chip` integration

The `rocket-chip` submodule is included so the integer side can reuse decode helpers and scalar-operation constants instead of duplicating that infrastructure locally.

The Mill build pulls in only the pieces it needs, so this repo uses `rocket-chip` as a library dependency rather than as a full SoC-generation flow.

## Toolchain and submodules

This snapshot targets Linux. The setup script installs or configures:

- Java 17
- Scala 2.13.16
- SBT 1.10.11
- Verilator
- Espresso

For a fresh checkout, initialize submodules once:

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

Generate a floating-point adder:

```bash
make verilog MODULE='HardFloat.AddRecFN(11, 53)'
```

Generate the integer ALU:

```bash
make verilog MODULE='HardInt.ALU(64)'
```

Generate the Booth multiplier core:

```bash
make verilog MODULE='HardInt.Radix4BoothMultiplier(64, 2)'
```

Generate the radix-4 SRT divider core:

```bash
make verilog MODULE='HardInt.Radix4SRTDivider(64)'
```

Generated outputs are written under:

```text
generated/verilog/<module path>/
```

For example:

```text
HardInt.Radix4SRTDivider(64)
→ generated/verilog/HardInt/Radix4SRTDivider_64/
```

For the full elaboration-wrapper help:

```bash
make elaborate-help
```

## Testing and verification

Run the full regression entrypoint:

```bash
make test
```

Run only floating-point verification:

```bash
make test-hardfloat
```

Run only integer verification:

```bash
make test-hardint
```

### HardFloat flow

The floating-point flow:

1. prepares Berkeley SoftFloat and TestFloat when needed
2. emits SystemVerilog into `generated/test_artifacts/HardFloat/...`
3. builds the Verilator model and matching C++ harnesses
4. drives Berkeley-generated vectors through the DUT

### HardInt flow

The integer flow:

1. elaborates the requested multiplier or divider test modules
2. builds Verilator harnesses under `generated/test_artifacts/HardInt/...`
3. runs exhaustive 15-bit and 16-bit stimulus from the checked-in C++ drivers

Set `VCD=1` before running tests if you want Verilator waveform tracing.

## Development workflow

Reformat only the project modules:

```bash
make reformat
```

Check formatting without rewriting files:

```bash
make check-format
```

Formatting intentionally skips submodules such as `rocket-chip`.

## Documentation and reference notes

Floating-point research notes remain under:

```text
HardFloat/docs/research/digit_recurrence/
```

That tree includes:

- the rendered PDF tutorial
- LaTeX source
- Python plot generators for radix-2 and radix-4 selection regions
- overlap-resolution notes for the optimized selector tables

Utility-level notes such as `HardUtils/docs/reference/LowMask.md` are also checked in.

## Tool versions

- Scala: 2.13.16
- Chisel: 7.5.0
- Mill: 1.0.6
- Scalafmt: 3.8.5

## License and third-party code

Unless noted otherwise, the repository is licensed under Apache License 2.0.

Third-party code and submodules remain separately licensed, especially the Berkeley HardFloat/TestFloat/SoftFloat content and the `rocket-chip` submodule.
