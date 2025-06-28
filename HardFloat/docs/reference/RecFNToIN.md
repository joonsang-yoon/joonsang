# `RecFNToIN.scala` Explanation

This document provides a detailed explanation of the `RecFNToIN.scala` file, which converts a floating-point number to a signed or unsigned integer.

## 1. Raw Float Conversion and Magnitude Check

The input recoded floating-point number is first converted to a raw format. Then, its magnitude is checked to determine if it's greater than or equal to one, or between 0.5 and 1.0.

### Code
```scala
val rawIn = RawFloatFromRecFN(expWidth, sigWidth, io.in)

val magGeOne = rawIn.sExp(expWidth)
val magHalfToOne = !rawIn.sExp(expWidth) && rawIn.sExp(expWidth - 1, 0).andR
```

### Explanations
- `rawIn`: The input floating-point number `in` is converted to the `RawFloat` format.
- `magGeOne`: A boolean flag that is true if the absolute value of the input floating-point number is greater than or equal to 1.0.
- `magHalfToOne`: A boolean flag that is true if the absolute value of the input floating-point number is between 0.5 and 1.0 (exclusive of 1.0).

## 2. Rounding Mode Definitions

This section defines boolean flags for each of the supported rounding modes.

### Code
```scala
val roundingMode_near_even = io.roundingMode === round_near_even
val roundingMode_minMag = io.roundingMode === round_minMag
val roundingMode_min = io.roundingMode === round_min
val roundingMode_max = io.roundingMode === round_max
val roundingMode_near_maxMag = io.roundingMode === round_near_maxMag
val roundingMode_odd = io.roundingMode === round_odd
```

## 3. Significand Alignment and Unrounded Integer

The significand is shifted and aligned to produce an unrounded integer value.

### Code
```scala
val shiftedSig = Cat(magGeOne, rawIn.sig(sigWidth - 2, 0)) << Mux(
  magGeOne,
  rawIn.sExp(min(expWidth - 2, log2Ceil(intWidth) - 1), 0),
  0.U
)
val alignedSig = Cat(shiftedSig(shiftedSig.getWidth - 1, sigWidth - 2), shiftedSig(sigWidth - 3, 0).orR)
val unroundedInt = alignedSig(alignedSig.getWidth - 1, 2).pad(intWidth)
```

### Explanations
- `shiftedSig`: The significand is shifted left based on the exponent to align it for integer conversion.
- `alignedSig`: The shifted significand is further processed to include a sticky bit for rounding.
- `unroundedInt`: The unrounded integer result is extracted from the aligned significand.

## 4. Rounding Logic

This section implements the rounding logic based on the chosen rounding mode.

### Code
```scala
val common_inexact = Mux(magGeOne, alignedSig(1, 0).orR, !rawIn.isZero)
val roundIncr_near_even = (magGeOne && (alignedSig(2, 1).andR || alignedSig(1, 0).andR)) || (magHalfToOne && alignedSig(1, 0).orR)
val roundIncr_near_maxMag = (magGeOne && alignedSig(1)) || magHalfToOne
val roundIncr = (roundingMode_near_even && roundIncr_near_even) ||
  (roundingMode_near_maxMag && roundIncr_near_maxMag) ||
  ((roundingMode_min || roundingMode_odd) && (rawIn.sign && common_inexact)) ||
  (roundingMode_max && (!rawIn.sign && common_inexact))
val complUnroundedInt = Mux(rawIn.sign, ~unroundedInt, unroundedInt)
val roundedInt = Mux(
  roundIncr ^ rawIn.sign,
  complUnroundedInt + 1.U,
  complUnroundedInt
) | (roundingMode_odd && common_inexact)
```

### Explanations
- `common_inexact`: A flag indicating if the conversion is inexact.
- `roundIncr_near_even`: Logic for rounding to the nearest even number.
- `roundIncr_near_maxMag`: Logic for rounding to the nearest number with the largest magnitude.
- `roundIncr`: A general flag indicating whether to increment the integer result based on the rounding mode.
- `complUnroundedInt`: The two's complement of the unrounded integer if the input is negative.
- `roundedInt`: The final rounded integer result.

## 5. Overflow Detection

This section detects if the conversion results in an overflow.

### Code
```scala
val magGeOne_atOverflowEdge = rawIn.sExp(expWidth - 2, 0) === (intWidth - 1).U
val roundCarryBut2 = unroundedInt(intWidth - 3, 0).andR && roundIncr
val common_overflow = Mux(
  magGeOne,
  (rawIn.sExp(expWidth - 2, 0) >= intWidth.U) || Mux(
    io.signedOut,
    Mux(
      rawIn.sign,
      magGeOne_atOverflowEdge && (unroundedInt(intWidth - 2, 0).orR || roundIncr),
      magGeOne_atOverflowEdge || ((rawIn.sExp(expWidth - 2, 0) === (intWidth - 2).U) && roundCarryBut2)
    ),
    rawIn.sign || (magGeOne_atOverflowEdge && unroundedInt(intWidth - 2) && roundCarryBut2)
  ),
  !io.signedOut && rawIn.sign && roundIncr
)
```

### Explanations
- `magGeOne_atOverflowEdge`: A flag that is true when the magnitude is at the edge of causing an overflow.
- `roundCarryBut2`: A flag that is true when a rounding increment causes a carry that propagates to the second-most significant bit.
- `common_overflow`: A flag that is true if the conversion results in an overflow. The logic depends on whether the output is signed or unsigned.

## 6. Exception Handling and Final Output

This section handles exceptions and sets the final output values.

### Code
```scala
val invalidExc = rawIn.isNaN || rawIn.isInf
val overflow = !invalidExc && common_overflow
val inexact = !invalidExc && !common_overflow && common_inexact

val excSign = !rawIn.isNaN && rawIn.sign
val excOut = Cat(io.signedOut === excSign, Replicate(intWidth - 1, !excSign))

io.out := Mux(invalidExc || common_overflow, excOut, roundedInt)
io.intExceptionFlags := Cat(invalidExc, overflow, inexact)
```

### Explanations
- `invalidExc`: An exception is raised if the input is NaN or infinity.
- `overflow`: The overflow flag is set if an overflow occurs and the operation is not invalid.
- `inexact`: The inexact flag is set if the conversion is inexact and there is no overflow or invalid operation.
- `excSign`: The sign to be used for the exception output.
- `excOut`: The output value in case of an exception (invalid operation or overflow).
- `io.out`: The final integer output.
- `io.intExceptionFlags`: The exception flags, including invalid, overflow, and inexact.
