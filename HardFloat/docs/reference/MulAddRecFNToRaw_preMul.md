# `MulAddRecFNToRaw_preMul` Explanation

This document provides a detailed explanation of the `MulAddRecFNToRaw_preMul` module in `HardFloat/src/main/scala/MulAddRecFN.scala`. This module handles the pre-multiplication steps of a fused multiply-add (FMA) operation.

## 1. Raw Float Conversion and Initial Calculations

The input recoded floating-point numbers are converted to a raw format. The sign of the product and the aligned exponent of the product are calculated.

### Code
```scala
val sigSumWidth = 3 * sigWidth + 3

val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.a)
val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.b)
val rawC = RawFloatFromRecFN(expWidth, sigWidth, io.c)

val signProd = rawA.sign ^ rawB.sign ^ io.op(1)
val sExpAlignedProd = (
  (rawA.sExp(expWidth, 0) +& rawB.sExp(expWidth, 0)) -& ((BigInt(1) << expWidth) - sigWidth - 3).U
).asSInt
```

### Explanations
- `sigSumWidth`: The width of the significand sum, which is `3 * sigWidth + 3`.
- `rawA`, `rawB`, `rawC`: The input floating-point numbers `a`, `b`, and `c` are converted to the `RawFloat` format.
- `signProd`: The sign of the product of `a` and `b`, taking into account the operation type.
- `sExpAlignedProd`: The aligned exponent of the product, calculated as `sExpA + sExpB - 2 * bias - 2 + sigWidth + 3`.

## 2. Subtraction and Operation Type

This section determines if a subtraction of magnitudes is required and clarifies the effective operation based on the input `op` signals.

### Code
```scala
val doSubMags = signProd ^ rawC.sign ^ io.op(0)
```

### Explanations
- `doSubMags`: A boolean flag that is true if the operation involves a subtraction of magnitudes.
- **Effective Operation:**
  - `op(1)=0, op(0)=0`: +A·B + C (FMA)
  - `op(1)=0, op(0)=1`: +A·B – C (FMS)
  - `op(1)=1, op(0)=0`: –A·B + C (FNMS)
  - `op(1)=1, op(0)=1`: –A·B – C (FNMA)

## 3. Alignment of C

This section calculates the alignment distance for the third operand `c` and aligns its significand.

### Code
```scala
val sNatCAlignDist = sExpAlignedProd - rawC.sExp(expWidth, 0).pad(expWidth + 3).asSInt
val posNatCAlignDist = sNatCAlignDist(expWidth + 1, 0)
val isMinCAlign = rawA.isZero || rawB.isZero || (sNatCAlignDist < 0.S)
val cIsDominant = !rawC.isZero && (isMinCAlign || (posNatCAlignDist <= sigWidth.U))
val cAlignDist = Mux(
  isMinCAlign,
  0.U,
  Mux(
    posNatCAlignDist < (sigSumWidth - 1).U,
    posNatCAlignDist(log2Ceil(sigSumWidth) - 1, 0),
    (sigSumWidth - 1).U
  )
)
val mainAlignedSigC = Cat(rawC.sig(sigWidth - 1, 0), 0.U((sigSumWidth - sigWidth + 2).W)) >> cAlignDist
val reduced4CExtra = (
  OrReduceBy4(rawC.sig(sigWidth - 1 - ((sigSumWidth - 1) & 3), 0) << ((sigSumWidth - sigWidth - 1) & 3)) &
    LowMask(cAlignDist(log2Ceil(sigSumWidth) - 1, 2), (sigSumWidth - 1) >> 2, (sigSumWidth - sigWidth - 1) >> 2)
).orR
val alignedSigC = Cat(
  mainAlignedSigC(sigSumWidth + 1, 3),
  (mainAlignedSigC(2, 0).orR || reduced4CExtra)
) ^ Replicate(sigSumWidth, doSubMags)
```

### Explanations
- `sNatCAlignDist`: The natural alignment distance for `c`.
- `posNatCAlignDist`: The positive part of the natural alignment distance.
- `isMinCAlign`: A flag that is true if `c` requires minimum alignment.
- `cIsDominant`: A flag that is true if `c` is the dominant operand.
- `cAlignDist`: The final alignment distance for `c`, clamped between 0 and `sigSumWidth - 1`.
- `mainAlignedSigC`: The main part of the aligned significand of `c`.
- `reduced4CExtra`: Extra bits for rounding, calculated from the significand of `c`.
- `alignedSigC`: The final aligned significand of `c`, potentially inverted for subtraction.

## 4. Outputs for Multiplication and Post-Multiplication Stages

This section sets the outputs that will be used in the multiplication and post-multiplication stages.

### Code
```scala
io.mulAddA := rawA.sig(sigWidth - 1, 0)
io.mulAddB := rawB.sig(sigWidth - 1, 0)
io.mulAddC := alignedSigC(2 * sigWidth, 1)

io.toPostMul.isSigNaNAny := IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || IsSigNaNRawFloat(rawC)
io.toPostMul.isNaNAOrB := rawA.isNaN || rawB.isNaN
io.toPostMul.isInfA := rawA.isInf
io.toPostMul.isZeroA := rawA.isZero
io.toPostMul.isInfB := rawB.isInf
io.toPostMul.isZeroB := rawB.isZero
io.toPostMul.signProd := signProd
io.toPostMul.isNaNC := rawC.isNaN
io.toPostMul.isInfC := rawC.isInf
io.toPostMul.isZeroC := rawC.isZero
io.toPostMul.sExpSum := Mux(cIsDominant, rawC.sExp, (sExpAlignedProd(expWidth + 1, 0) - sigWidth.U).asSInt)
io.toPostMul.doSubMags := doSubMags
io.toPostMul.cIsDominant := cIsDominant
io.toPostMul.cDom_cAlignDist := cAlignDist(log2Ceil(sigWidth + 1) - 1, 0)
io.toPostMul.highAlignedSigC := alignedSigC(sigSumWidth - 1, 2 * sigWidth + 1)
io.toPostMul.bit0AlignedSigC := alignedSigC(0)
```

### Explanations
- `io.mulAddA`, `io.mulAddB`, `io.mulAddC`: The significands of `a`, `b`, and the aligned `c` are sent to the multiplier.
- `io.toPostMul...`: Various status flags and values are passed to the post-multiplication stage, including NaN, infinity, zero, sign, exponent sum, and alignment information.
