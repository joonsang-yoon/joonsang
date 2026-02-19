# Chisel Arithmetic Library

This snapshot keeps the mixed floating-point and integer scope from the previous revision, but it changes the floating-point multiply path in a major way. `MulRecFN` and `MulAddRecFN` are now staged, latency-bearing modules with `Decoupled` request/response interfaces, an explicit split between pre-processing and reduction stages, and tests that align expected values with pipelined responses.

## What changed in this snapshot

- `MulRecFN(expWidth, sigWidth, initHeight)` is now a staged multiplier instead of a direct combinational-style wrapper
- `MulAddRecFN(expWidth, sigWidth, initHeight)` follows the same staged, handshake-driven structure
- the multiply path is organized around pre-stage logic, Dadda-style reduction, `FinalAdder`, and post/rounding logic
- HardFloat tests queue expected results so they can compare cleanly against latency-bearing outputs
- `HardInt` and the rest of the repository stay available as in the previous revision

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

The floating-point library still includes the expected arithmetic and conversion blocks:

- `AddRecFN`
- `MulRecFN`
- `MulAddRecFN`
- `CompareRecFN`
- `DivSqrtRecFN`
- `INToRecFN`
- `RecFNToIN`
- `RecFNToRecFN`
- `RoundAnyRawFNToRecFN`

The multiply path is the main difference in this snapshot.

`MulRecFN(expWidth, sigWidth, initHeight)` and `MulAddRecFN(expWidth, sigWidth, initHeight)` are now latency-bearing modules with:

- explicit request/response handshakes
- dedicated pre- and post-processing stages
- internal reduction stages
- Dadda-style partial-product reduction and `FinalAdder`-based finishing logic

That makes the codebase much better suited to pipelined execution units than to purely combinational wrappers.

Typical width presets remain:

- half precision: `(5, 11)`
- single precision: `(8, 24)`
- double precision: `(11, 53)`

### HardInt

`HardInt` continues to provide the integer execution blocks introduced in the previous snapshot:

- `ALU(dataWidth)`
- `Radix4BoothMultiplier(dataWidth, initHeight)`
- `RISCVMultiplier(...)`
- `Radix4SRTDivider(dataWidth)`
- `RISCVDivider(...)`

These modules still rely on `rocket-chip` decode/constants support and keep the same Verilator-backed test flow.

### HardUtils

`HardUtils` remains the shared implementation layer. Relevant helpers in this snapshot include:

- bit utilities such as `CountLeadingZeros`, `LowMask`, `OrReduceBy2`, and `OrReduceBy4`
- reduction-tree helpers and compressor primitives
- pipeline, skid, and iterative skid buffers
- `FinalAdder`
- concatenation-order helpers

## Toolchain and submodules

This repository targets Linux. The setup flow installs or configures:

- Java 17
- Scala 2.13.16
- SBT 1.10.11
- Verilator
- Espresso

Initialize the submodules on a new checkout:

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

Generate the demo module:

```bash
make verilog MODULE=TopLevelModule.CustomDesign
```

Generate the staged floating-point multiplier:

```bash
make verilog MODULE='HardFloat.MulRecFN(11, 53, 3)'
```

Generate the staged fused multiply-add block:

```bash
make verilog MODULE='HardFloat.MulAddRecFN(11, 53, 3)'
```

Generate the radix-4 Booth integer multiplier:

```bash
make verilog MODULE='HardInt.Radix4BoothMultiplier(64, 2)'
```

Generate the radix-4 SRT divider:

```bash
make verilog MODULE='HardInt.Radix4SRTDivider(64)'
```

Generated SystemVerilog is written under:

```text
generated/verilog/<module path>/
```

For example:

```text
HardFloat.MulRecFN(11, 53, 3)
→ generated/verilog/HardFloat/MulRecFN_11_53_3/
```

For the full wrapper help, including ChiselStage options:

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

### Floating-point validation notes

The HardFloat regression flow has been updated to match the staged multiplier and FMA implementations. The test modules queue expected values so the harness can compare them against the pipelined request/response outputs cleanly.

That keeps the Berkeley-vector-based reference flow in place while supporting a more realistic latency-bearing implementation.

### Integer validation notes

The integer side continues to elaborate Verilator harnesses under `generated/test_artifacts/HardInt/...` and runs exhaustive 15-bit and 16-bit combinations for the shipped multiplier/divider checks.

Set `VCD=1` before running tests if you want waveform tracing from the Verilator flow.

## Development workflow

Reformat only the project modules:

```bash
make reformat
```

Check formatting without rewriting files:

```bash
make check-format
```

Formatting is intentionally limited to the project code and does not recurse into `rocket-chip`.

## Documentation and reference notes

The digit-recurrence write-up remains available under:

```text
HardFloat/docs/research/digit_recurrence/
```

That directory includes:

- rendered PDF documentation
- LaTeX source
- Python plot generators for radix-2 and radix-4 selector tables
- notes describing the deterministic overlap-resolution transform

## Tool versions

- Scala: 2.13.16
- Chisel: 7.5.0
- Mill: 1.0.6
- Scalafmt: 3.8.5

## License and third-party code

Unless noted otherwise, the repository is licensed under Apache License 2.0.

Third-party code and submodules remain separately licensed, especially the Berkeley floating-point sources and `rocket-chip`.
