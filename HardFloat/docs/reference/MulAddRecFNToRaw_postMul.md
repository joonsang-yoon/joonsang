# `MulAddRecFNToRaw_postMul` Explanation

This document provides a detailed explanation of the `MulAddRecFNToRaw_postMul` module in `HardFloat/src/main/scala/MulAddRecFN.scala`. This module handles the post-multiplication steps of a fused multiply-add (FMA) operation.

## 1. Absolute Difference Calculation

This section defines a helper function to compute the absolute difference of two numbers using an End-Around Carry (EAC) adder.

### Code
```scala
val temp = io.a +& ~io.b
io.absDiff := Mux(temp(width), temp(width - 1, 0), ~temp(width - 1, 0)) + temp(width)
```

### Explanations
- `temp`: The sum of `a` and the one's complement of `b`.
- `io.absDiff`: The absolute difference between `a` and `b`.
  - If `a - b > 0`, the carry-out `temp(width)` is 1, and `absDiff` is `(a - b) - 1 + 1 = a - b`.
  - If `a - b = 0`, the carry-out is 0, and `absDiff` is `b - a = 0`.
  - If `a - b < 0`, the carry-out is 0, and `absDiff` is `b - a > 0`.

## 2. Significand Summation

This section calculates the sum of the significands from the multiplication result and the third operand `c`.

### Code
```scala
val sigSumWidth = 3 * sigWidth + 3

val roundingMode_min = io.roundingMode === round_min

val opSignC = io.fromPreMul.signProd ^ io.fromPreMul.doSubMags
val sigSum = Cat(
  Mux(
    io.mulAddResult(2 * sigWidth),
    io.fromPreMul.highAlignedSigC + 1.U,
    io.fromPreMul.highAlignedSigC
  ),
  io.mulAddResult(2 * sigWidth - 1, 0),
  io.fromPreMul.bit0AlignedSigC
)
```

### Explanations
- `sigSumWidth`: The width of the significand sum.
- `roundingMode_min`: A flag for the `round_min` rounding mode.
- `opSignC`: The effective sign of operand `c`.
- `sigSum`: The sum of the significands, representing a number with 1 integer bit and `3 * sigWidth + 2` fractional bits.

## 3. Handling the Case Where C is Dominant

This section handles the logic for when the third operand `c` is the dominant term in the FMA operation.

### Code
```scala
val cDom_sign = opSignC
val cDom_sExp = (io.fromPreMul.sExpSum(expWidth, 0) - io.fromPreMul.doSubMags).pad(expWidth + 2).asSInt
val cDom_absSigSum = Mux(
  io.fromPreMul.doSubMags,
  ~sigSum(sigSumWidth - 1, sigWidth + 1),
  Cat(false.B, io.fromPreMul.highAlignedSigC(sigWidth + 1, sigWidth), sigSum(sigSumWidth - 3, sigWidth + 2))
)
val cDom_absSigSumExtra = Mux(
  io.fromPreMul.doSubMags,
  (~sigSum(sigWidth, 1)).orR,
  sigSum(sigWidth + 1, 1).orR
)
val cDom_mainSig = (cDom_absSigSum << io.fromPreMul.cDom_cAlignDist)(2 * sigWidth + 1, sigWidth - 3)
val cDom_reduced4SigExtra = (
  OrReduceBy4(cDom_absSigSum(sigWidth - 4, 0) << (3 - (sigWidth & 3))) &
    LowMask(io.fromPreMul.cDom_cAlignDist(log2Ceil(sigWidth + 1) - 1, 2), 0, sigWidth >> 2)
).orR
val cDom_sig = Cat(
  cDom_mainSig(sigWidth + 4, 3),
  (cDom_mainSig(2, 0).orR || cDom_reduced4SigExtra || cDom_absSigSumExtra)
)
```

### Explanations
- `cDom_sign`: The sign of the result when `c` is dominant.
- `cDom_sExp`: The exponent of the result when `c` is dominant.
- `cDom_absSigSum`: The absolute value of the significand sum.
- `cDom_absSigSumExtra`: Extra bits for rounding.
- `cDom_mainSig`: The main part of the significand.
- `cDom_reduced4SigExtra`: Extra bits for rounding, calculated from the absolute significand sum.
- `cDom_sig`: The final significand when `c` is dominant, representing a number with 2 integer bits and `sigWidth + 1` fractional bits.

## 4. Handling the Case Where C is Not Dominant

This section handles the logic for when the product term is dominant.

### Code
```scala
val notCDom_signSigSum = sigSum(2 * sigWidth + 3)
val notCDom_absSigSum = Mux(
  notCDom_signSigSum,
  ~sigSum(2 * sigWidth + 2, 0),
  sigSum(2 * sigWidth + 2, 0) + io.fromPreMul.doSubMags
)
val notCDom_reduced2AbsSigSum = OrReduceBy2(notCDom_absSigSum)
val notCDom_normDistReduced2 = CountLeadingZeros(notCDom_reduced2AbsSigSum)
val notCDom_nearNormDist = Cat(notCDom_normDistReduced2, false.B)
val notCDom_sExp = io.fromPreMul.sExpSum - notCDom_nearNormDist.pad(expWidth + 2).asSInt
val notCDom_mainSig = (notCDom_absSigSum << notCDom_nearNormDist)(2 * sigWidth + 3, sigWidth - 1)
val notCDom_reduced4SigExtra = (
  OrReduceBy2(notCDom_reduced2AbsSigSum((sigWidth >> 1) - 1, 0) << (1 - (((sigWidth + 2) >> 1) & 1))) &
    LowMask(notCDom_normDistReduced2(log2Ceil(sigWidth + 2) - 1, 1), 0, (sigWidth + 2) >> 2)
).orR
val notCDom_sig = Cat(
  notCDom_mainSig(sigWidth + 4, 3),
  (notCDom_mainSig(2, 0).orR || notCDom_reduced4SigExtra)
)
val notCDom_completeCancellation = notCDom_sig(sigWidth + 2, sigWidth + 1) === 0.U
val notCDom_sign = Mux(notCDom_completeCancellation, roundingMode_min, io.fromPreMul.signProd ^ notCDom_signSigSum)
```

### Explanations
- `notCDom_signSigSum`: The sign of the significand sum.
- `notCDom_absSigSum`: The absolute value of the significand sum.
- `notCDom_reduced2AbsSigSum`: The absolute significand sum reduced by a factor of 2.
- `notCDom_normDistReduced2`: The normalization distance.
- `notCDom_nearNormDist`: The near normalization distance.
- `notCDom_sExp`: The exponent of the result.
- `notCDom_mainSig`: The main part of the significand.
- `notCDom_reduced4SigExtra`: Extra bits for rounding.
- `notCDom_sig`: The final significand, representing a number with 2 integer bits and `sigWidth + 1` fractional bits.
- `notCDom_completeCancellation`: A flag for complete cancellation.
- `notCDom_sign`: The sign of the result.

## 5. Special Case Handling and Final Output

This section handles special cases like infinity and NaN, and sets the final output values.

### Code
```scala
val notNaN_isInfProd = io.fromPreMul.isInfA || io.fromPreMul.isInfB
val notNaN_isInfOut = notNaN_isInfProd || io.fromPreMul.isInfC
val notNaN_addZeros = (io.fromPreMul.isZeroA || io.fromPreMul.isZeroB) && io.fromPreMul.isZeroC

io.invalidExc := io.fromPreMul.isSigNaNAny ||
  (io.fromPreMul.isInfA && io.fromPreMul.isZeroB) ||
  (io.fromPreMul.isZeroA && io.fromPreMul.isInfB) ||
  (!io.fromPreMul.isNaNAOrB && notNaN_isInfProd && io.fromPreMul.isInfC && io.fromPreMul.doSubMags)
io.rawOut.isNaN := io.fromPreMul.isNaNAOrB || io.fromPreMul.isNaNC
io.rawOut.isInf := notNaN_isInfOut
io.rawOut.isZero := notNaN_addZeros || (!io.fromPreMul.cIsDominant && notCDom_completeCancellation)
io.rawOut.sign := (notNaN_isInfProd && io.fromPreMul.signProd) ||
  (io.fromPreMul.isInfC && opSignC) ||
  (notNaN_addZeros && !roundingMode_min && io.fromPreMul.signProd && opSignC) ||
  (notNaN_addZeros && roundingMode_min && (io.fromPreMul.signProd || opSignC)) ||
  (!notNaN_isInfOut && !notNaN_addZeros && Mux(io.fromPreMul.cIsDominant, cDom_sign, notCDom_sign))
io.rawOut.sExp := Mux(io.fromPreMul.cIsDominant, cDom_sExp, notCDom_sExp)
io.rawOut.sig := Mux(io.fromPreMul.cIsDominant, cDom_sig, notCDom_sig)
```

### Explanations
- `notNaN_isInfProd`: True if the product of A and B is infinity.
- `notNaN_isInfOut`: True if the final result is infinity.
- `notNaN_addZeros`: True if the operation is an addition of zeros.
- `io.invalidExc`: The invalid exception flag.
- `io.rawOut.isNaN`, `io.rawOut.isInf`, `io.rawOut.isZero`: Flags for NaN, infinity, and zero.
- `io.rawOut.sign`, `io.rawOut.sExp`, `io.rawOut.sig`: The final sign, exponent, and significand of the result.
