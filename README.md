# Chisel Arithmetic Library

This snapshot keeps the staged floating-point and integer execution blocks from the previous revision and expands `HardUtils` with a reusable `Decoupled` crossbar/switch. The repository is now useful both as an arithmetic library and as a source of small, integration-friendly building blocks for larger Chisel systems.

## What changed in this snapshot

- `HardFloat` keeps the staged multiplier and fused multiply-add datapaths introduced previously
- `HardInt` still provides ALU, Booth multiplier, and radix-4 SRT divider implementations
- `HardUtils` now adds a generic unicast `XbarSwitch` for `Decoupled` traffic
- named example wrappers make the switch easy to elaborate directly for RTL inspection
- the existing floating-point, integer, and documentation flows remain in place

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

The floating-point library still covers:

- `AddRecFN`
- `MulRecFN`
- `MulAddRecFN`
- `CompareRecFN`
- `DivSqrtRecFN`
- `INToRecFN`
- `RecFNToIN`
- `RecFNToRecFN`
- `RoundAnyRawFNToRecFN`

`MulRecFN(expWidth, sigWidth, initHeight)` and `MulAddRecFN(expWidth, sigWidth, initHeight)` remain staged, handshake-driven modules intended for latency-bearing datapaths rather than purely combinational wrappers.

### HardInt

The integer side includes:

- `ALU(dataWidth)`
- `Radix4BoothMultiplier(dataWidth, initHeight)`
- `RISCVMultiplier(...)`
- `Radix4SRTDivider(dataWidth)`
- `RISCVDivider(...)`

These blocks continue to lean on the `rocket-chip` submodule for decode/constants support and keep the same Verilator-based regression flow.

### HardUtils

`HardUtils` now covers both arithmetic support logic and data-movement infrastructure.

Arithmetic-facing helpers include:

- `CountLeadingZeros`, `LowMask`, `OrReduceBy2`, and `OrReduceBy4`
- compressor primitives and reduction-tree helpers
- pipeline, skid, and iterative skid buffers
- `FinalAdder`
- `ConcatOrder`

New in this snapshot is **`XbarSwitch`**, a generic unicast crossbar for `Decoupled` traffic.

The source file introduces:

- `XbarSwitchReq` and `XbarSwitchIO` bundle definitions
- the generic `XbarSwitch` module itself
- fixed-priority, round-robin, locking, and locking-round-robin wrappers
- elaboratable example modules such as `FixedPriorityXbarSwitchExample`

In practice, `XbarSwitch` is the sort of utility you embed inside a larger design rather than use as a final top level, but the example wrappers make it easy to inspect the generated RTL directly.

## Toolchain and submodules

This repository targets Linux. The setup flow installs or configures:

- Java 17
- Scala 2.13.16
- SBT 1.10.11
- Verilator
- Espresso

For a fresh checkout:

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

Generate the floating-point multiplier:

```bash
make verilog MODULE='HardFloat.MulRecFN(11, 53, 3)'
```

Generate the fused multiply-add block:

```bash
make verilog MODULE='HardFloat.MulAddRecFN(11, 53, 3)'
```

Generate the integer ALU:

```bash
make verilog MODULE='HardInt.ALU(64)'
```

Generate the integer divider:

```bash
make verilog MODULE='HardInt.Radix4SRTDivider(64)'
```

Generate one of the checked-in crossbar examples:

```bash
make verilog MODULE='HardUtils.FixedPriorityXbarSwitchExample'
```

Generated SystemVerilog is written under:

```text
generated/verilog/<module path>/
```

For the full wrapper help, including ChiselStage options:

```bash
make elaborate-help
```

## Testing and verification

Run everything:

```bash
make test
```

Floating-point only:

```bash
make test-hardfloat
```

Integer only:

```bash
make test-hardint
```

The floating-point flow still uses Berkeley SoftFloat/TestFloat-generated vectors, while the integer flow still uses the checked-in Verilator harnesses for exhaustive 15-bit and 16-bit regression checks.

Set `VCD=1` before running tests if you want Verilator waveform tracing.

## Development workflow

Reformat project Scala sources:

```bash
make reformat
```

Check formatting without rewriting files:

```bash
make check-format
```

Formatting remains intentionally restricted to the project code and does not recurse into `rocket-chip`.

## Documentation and reference notes

Floating-point research material remains available under:

```text
HardFloat/docs/research/digit_recurrence/
```

That tree includes the rendered PDF, LaTeX source, Python plot generators, and notes for the optimized overlap-resolution transform.

Utility-level notes such as `HardUtils/docs/reference/LowMask.md` are also checked in.

## Tool versions

- Scala: 2.13.16
- Chisel: 7.5.0
- Mill: 1.0.6
- Scalafmt: 3.8.5

## License and third-party code

Unless noted otherwise, the repository is licensed under Apache License 2.0.

Third-party code and submodules remain separately licensed, especially the Berkeley floating-point sources and `rocket-chip`. See `THIRD_PARTY_NOTICES.md` for the attribution summary.
