# `AddRecFN.scala` Explanation

This document provides a detailed explanation of the `AddRecFN.scala` file, which implements floating-point addition and subtraction.

## 1. Alignment and Initial Checks

This section calculates the alignment distance between the two input floating-point numbers and sets up some initial flags based on their signs and exponents.

### Code
```scala
val alignDistWidth = log2Ceil(sigWidth + 3)
val effSignB = io.b.sign ^ io.subOp
val eqSigns = io.a.sign === effSignB
val notEqSigns_signZero = io.roundingMode === round_min
val sDiffExps = (io.a.sExp(expWidth, 0) -& io.b.sExp(expWidth, 0)).asSInt
val modNatAlignDist = Mux(sDiffExps < 0.S, -sDiffExps(alignDistWidth - 1, 0), sDiffExps(alignDistWidth - 1, 0))
val isMaxAlign = (sDiffExps(expWidth + 1, alignDistWidth).asSInt =/= 0.S) && (
  (sDiffExps(expWidth + 1, alignDistWidth).asSInt =/= -1.S) || (sDiffExps(alignDistWidth - 1, 0) === 0.U)
)
val alignDist = Mux(isMaxAlign, Replicate(alignDistWidth, true.B), modNatAlignDist)
val closeSubMags = !eqSigns && !isMaxAlign && (modNatAlignDist <= 1.U)
```

### Explanations
- `alignDistWidth`: The width of the alignment distance. It can range from 0 to `sigWidth` + 2.
- `effSignB`: The effective sign of the second operand `b`, considering the `subOp` flag.
- `eqSigns`: A boolean flag that is true if the signs of the two operands are equal (for addition) or opposite (for subtraction).
- `notEqSigns_signZero`: A flag that is true when the signs are not equal and the rounding mode is `round_min`. In this case, the result is zero.
- `sDiffExps`: The difference between the exponents of the two operands.
- `modNatAlignDist`: The natural alignment distance, which is the absolute difference of the exponents modulo 2<sup>`alignDistWidth`</sup>.
- `isMaxAlign`: A flag that is true if the difference in exponents is greater than or equal to 2<sup>`alignDistWidth`</sup> or less than or equal to -2<sup>`alignDistWidth`</sup>.
- `alignDist`: The final alignment distance, which is the minimum of the absolute difference of the exponents and 2<sup>`alignDistWidth`</sup> - 1.
- `closeSubMags`: A flag that is true when we are performing a subtraction of two numbers with close magnitudes, i.e., the absolute difference of their exponents is less than or equal to 1.

## 2. Handling Close Magnitudes Subtraction

This section handles the case where we are subtracting two numbers with close magnitudes.

### Code
```scala
val close_alignedSigA = Mux1H(
  Seq(
    (sDiffExps < 0.S) -> io.a.sig(sigWidth - 1, 0),
    ((sDiffExps >= 0.S) && !sDiffExps(0)) -> Cat(io.a.sig(sigWidth - 1, 0), false.B),
    ((sDiffExps >= 0.S) && sDiffExps(0)) -> Cat(io.a.sig(sigWidth - 1, 0), 0.U(2.W))
  )
)
val close_sSigSum = (close_alignedSigA -& Cat(false.B, io.b.sig(sigWidth - 1, 0), false.B)).asSInt
val close_sigSum = Mux(close_sSigSum < 0.S, -close_sSigSum(sigWidth + 1, 0), close_sSigSum(sigWidth + 1, 0))
val close_adjustedSigSum = close_sigSum << (sigWidth & 1)
val close_reduced2SigSum = OrReduceBy2(close_adjustedSigSum)
val close_normDistReduced2 = CountLeadingZeros(close_reduced2SigSum)
val close_nearNormDist = Cat(close_normDistReduced2, false.B)
val close_sigOut = Cat((close_sigSum << close_nearNormDist)(sigWidth + 1, 0), false.B)
val close_totalCancellation = close_sigOut(sigWidth + 2, sigWidth + 1) === 0.U
val close_notTotalCancellation_signOut = io.a.sign ^ (close_sSigSum < 0.S)
```

### Explanations
- `close_alignedSigA`: The significand of `a` is aligned based on the exponent difference.
- `close_sSigSum`: The signed sum of the aligned significands.
- `close_sigSum`: The absolute value of the sum of the aligned significands. It represents a number with 2 integer bits and `sigWidth` fractional bits.
- `close_adjustedSigSum`: The `close_sigSum` is shifted left by 1 if `sigWidth` is odd.
- `close_reduced2SigSum`: The `close_adjustedSigSum` is reduced by a factor of 2 by ORing adjacent bits.
- `close_normDistReduced2`: The number of leading zeros in `close_reduced2SigSum`, which is used to normalize the result.
- `close_nearNormDist`: The normalization distance.
- `close_sigOut`: The normalized significand of the result. It represents a number with 2 integer bits and `sigWidth` + 1 fractional bits.
- `close_totalCancellation`: A flag that is true if the subtraction results in a total cancellation (i.e., the result is zero).
- `close_notTotalCancellation_signOut`: The sign of the result when there is no total cancellation.

## 3. Handling Far Magnitudes or Addition

This section handles the case where the magnitudes of the two numbers are far apart, or when we are performing an addition.

### Code
```scala
val far_signOut = Mux(sDiffExps < 0.S, effSignB, io.a.sign)
val far_sigLarger = Mux(sDiffExps < 0.S, io.b.sig(sigWidth - 1, 0), io.a.sig(sigWidth - 1, 0))
val far_sigSmaller = Mux(sDiffExps < 0.S, io.a.sig(sigWidth - 1, 0), io.b.sig(sigWidth - 1, 0))
val far_mainAlignedSigSmaller = Cat(far_sigSmaller, 0.U(5.W)) >> alignDist
val far_reduced4SigSmaller = OrReduceBy4(Cat(far_sigSmaller, 0.U(2.W)))
val far_roundExtraMask = LowMask(alignDist(alignDistWidth - 1, 2), (sigWidth + 5) >> 2, 0)
val far_alignedSigSmaller = Cat(
  far_mainAlignedSigSmaller(sigWidth + 4, 3),
  (far_mainAlignedSigSmaller(2, 0).orR || (far_reduced4SigSmaller & far_roundExtraMask).orR)
)
val far_subMags = !eqSigns
val far_negAlignedSigSmaller = Cat(false.B, far_alignedSigSmaller) ^ Replicate(sigWidth + 4, far_subMags)
val far_sigSum = Cat(false.B, far_sigLarger, 0.U(3.W)) + far_negAlignedSigSmaller + far_subMags
val far_sigOut = Mux(far_subMags, far_sigSum(sigWidth + 2, 0), far_sigSum(sigWidth + 3, 1) | far_sigSum(0))
```

### Explanations
- `far_signOut`: The sign of the result is the sign of the number with the larger magnitude.
- `far_sigLarger`: The significand of the number with the larger magnitude.
- `far_sigSmaller`: The significand of the number with the smaller magnitude.
- `far_mainAlignedSigSmaller`: The significand of the smaller number is aligned by shifting it right by `alignDist`.
- `far_reduced4SigSmaller`: The `far_sigSmaller` is reduced by a factor of 4 by ORing groups of 4 adjacent bits.
- `far_roundExtraMask`: A mask used for rounding.
- `far_alignedSigSmaller`: The aligned significand of the smaller number, with rounding information.
- `far_subMags`: A flag that is true when we are performing a subtraction.
- `far_negAlignedSigSmaller`: The negated (two's complement) aligned significand of the smaller number, used for subtraction.
- `far_sigSum`: The sum of the aligned significands. It represents a number with 2 integer bits and `sigWidth` + 2 fractional bits.
- `far_sigOut`: The significand of the result. It represents a number with 2 integer bits and `sigWidth` + 1 fractional bits.

## 4. Special Cases: Infinity and Zero

This section handles special cases such as infinity and zero.

### Code
```scala
val notSigNaN_invalidExc = io.a.isInf && io.b.isInf && !eqSigns
val notNaN_isInfOut = io.a.isInf || io.b.isInf
val addZeros = io.a.isZero && io.b.isZero
val notNaN_specialCase = notNaN_isInfOut || addZeros
val notNaN_isZeroOut = addZeros || (!notNaN_isInfOut && closeSubMags && close_totalCancellation)
val notNaN_signOut = (eqSigns && io.a.sign) ||
  (io.a.isInf && io.a.sign) ||
  (io.b.isInf && effSignB) ||
  (notNaN_isZeroOut && !eqSigns && notEqSigns_signZero) ||
  (!notNaN_specialCase && closeSubMags && !close_totalCancellation && close_notTotalCancellation_signOut) ||
  (!notNaN_specialCase && !closeSubMags && far_signOut)
```

### Explanations
- `notSigNaN_invalidExc`: An exception is raised for `Inf - Inf`.
- `notNaN_isInfOut`: The output is infinity if either of the inputs is infinity.
- `addZeros`: A flag that is true if both inputs are zero.
- `notNaN_specialCase`: A flag that is true if the operation involves infinity or zero.
- `notNaN_isZeroOut`: The output is zero if both inputs are zero, or if there is a total cancellation in a subtraction.
- `notNaN_signOut`: The sign of the output. The logic handles various cases, including infinity, zero, and normal operations.

## 5. Final Output Calculation

This section calculates the final exponent and significand of the result.

### Code
```scala
val common_sExpOut = (Mux(
  closeSubMags || (sDiffExps < 0.S),
  io.b.sExp(expWidth, 0),
  io.a.sExp(expWidth, 0)
) -& Mux(
  closeSubMags,
  close_nearNormDist,
  far_subMags
)).asSInt
val common_sigOut = Mux(closeSubMags, close_sigOut, far_sigOut)
```

### Explanations
- `common_sExpOut`: The exponent of the result is calculated based on the exponents of the inputs and the normalization distance.
  - If `closeSubMags` is true, `common_sExpOut = sExpB - close_nearNormDist`.
  - If `!closeSubMags` and `far_subMags` are true, `common_sExpOut = max(sExpA, sExpB) - 1`.
  - If `!closeSubMags` and `!far_subMags` are true, `common_sExpOut = max(sExpA, sExpB)`.
- `common_sigOut`: The significand of the result is selected based on whether the magnitudes were close or far.

## 6. Setting Output Signals

This section sets the final output signals of the module.

### Code
```scala
io.invalidExc := IsSigNaNRawFloat(io.a) || IsSigNaNRawFloat(io.b) || notSigNaN_invalidExc
io.rawOut.isNaN := io.a.isNaN || io.b.isNaN
io.rawOut.isInf := notNaN_isInfOut
io.rawOut.isZero := notNaN_isZeroOut
io.rawOut.sign := notNaN_signOut
io.rawOut.sExp := common_sExpOut
io.rawOut.sig := common_sigOut
```

### Explanations
- `io.invalidExc`: The invalid exception flag is set if either input is a signaling NaN or if the operation is `Inf - Inf`.
- `io.rawOut.isNaN`: The output is NaN if either input is NaN.
- `io.rawOut.isInf`: The output is infinity if the `notNaN_isInfOut` flag is set.
- `io.rawOut.isZero`: The output is zero if the `notNaN_isZeroOut` flag is set.
- `io.rawOut.sign`: The sign of the output.
- `io.rawOut.sExp`: The exponent of the output.
- `io.rawOut.sig`: The significand of the output.
