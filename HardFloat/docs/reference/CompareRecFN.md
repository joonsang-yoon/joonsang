# `CompareRecFN.scala` Explanation

This document provides a detailed explanation of the `CompareRecFN.scala` file, which implements the comparison of two floating-point numbers.

## 1. Raw Float Conversion and Basic Properties

First, the input recoded floating-point numbers are converted to a raw format. Then, basic properties like whether the numbers are ordered (not NaN), infinity, or zero are determined.

### Code
```scala
val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.a)
val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.b)

val ordered = !rawA.isNaN && !rawB.isNaN
val bothInfs = rawA.isInf && rawB.isInf
val bothZeros = rawA.isZero && rawB.isZero
```

### Explanations
- `rawA`, `rawB`: The input floating-point numbers `a` and `b` are converted to the `RawFloat` format.
- `ordered`: A boolean flag that is true if neither of the inputs is NaN.
- `bothInfs`: A boolean flag that is true if both inputs are infinity.
- `bothZeros`: A boolean flag that is true if both inputs are zero.

## 2. Magnitude Comparison

This section compares the magnitudes of the two numbers.

### Code
```scala
val eqExps = rawA.sExp(expWidth, 0) === rawB.sExp(expWidth, 0)
val common_ltMags = (rawA.sExp(expWidth, 0) < rawB.sExp(expWidth, 0)) || (eqExps && (rawA.sig(sigWidth - 2, 0) < rawB.sig(sigWidth - 2, 0)))
val common_eqMags = eqExps && (rawA.sig(sigWidth - 2, 0) === rawB.sig(sigWidth - 2, 0))
```

### Explanations
- `eqExps`: A boolean flag that is true if the exponents of the two numbers are equal.
- `common_ltMags`: A boolean flag that is true if the magnitude of `a` is less than the magnitude of `b`.
- `common_eqMags`: A boolean flag that is true if the magnitudes of the two numbers are equal.

## 3. Comparison Logic

This section implements the core comparison logic based on the signs and magnitudes.

### Code
```scala
val ordered_lt = !bothZeros && (
  (rawA.sign && !rawB.sign) ||
    (!bothInfs && (
      (rawA.sign && !common_ltMags && !common_eqMags) ||
        (!rawB.sign && common_ltMags)
    ))
)
val ordered_eq = bothZeros || ((rawA.sign === rawB.sign) && (bothInfs || common_eqMags))
```

### Explanations
- `ordered_lt`: A boolean flag that is true if `a` is less than `b`, considering the signs. This is only valid if the numbers are `ordered`.
- `ordered_eq`: A boolean flag that is true if `a` is equal to `b`. This is only valid if the numbers are `ordered`.

## 4. Invalid Operation and Exception Handling

This section determines if the comparison is invalid, which can happen with signaling NaNs or when unordered inputs are compared with signaling enabled.

### Code
```scala
val invalid = IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || (io.signaling && !ordered)
```

### Explanations
- `invalid`: A boolean flag that indicates an invalid operation. The conditions for this flag depend on the type of comparison being performed, which is controlled by `io.signaling`:
  - **FEQ (signaling = false):** `invalid` is true if either input is a signaling NaN.
    - `invalid = IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB)`
  - **FLT/FLE (signaling = true):** `invalid` is true if either input is any kind of NaN (since `!ordered` will be true).
    - `invalid = IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || !ordered` which simplifies to `rawA.isNaN || rawB.isNaN`.

## 5. Final Output

Finally, the output signals are set based on the comparison results.

### Code
```scala
io.lt := ordered && ordered_lt
io.eq := ordered && ordered_eq
io.gt := ordered && !ordered_lt && !ordered_eq
io.exceptionFlags := Cat(invalid, 0.U(4.W))
```

### Explanations
- `io.lt`: The "less than" output. It is true only if the inputs are ordered and `a` is less than `b`.
- `io.eq`: The "equal to" output. It is true only if the inputs are ordered and `a` is equal to `b`.
- `io.gt`: The "greater than" output. It is true only if the inputs are ordered and `a` is greater than `b`.
- `io.exceptionFlags`: The exception flags, with the `invalid` flag being the most significant bit.

## Comparison Truth Tables

### Less Than (`io.lt`) Truth Table

| `a` \ `b` | -Inf  | Neg   | -0    | +0    | Pos   | +Inf  | NaN   |
| :-------- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **-Inf**  | `F`   | `T`   | `T`   | `T`   | `T`   | `T`   | `F`   |
| **Neg**   | `F`   | `\|a\|>\|b\|` | `T`   | `T`   | `T`   | `T`   | `F`   |
| **-0**    | `F`   | `F`   | `F`   | `F`   | `T`   | `T`   | `F`   |
| **+0**    | `F`   | `F`   | `F`   | `F`   | `T`   | `T`   | `F`   |
| **Pos**   | `F`   | `F`   | `F`   | `F`   | `\|a\|<\|b\|` | `T`   | `F`   |
| **+Inf**  | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   |
| **NaN**   | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   |

### Equality (`io.eq`) Truth Table

| `a` \ `b` | -Inf  | Neg   | -0    | +0    | Pos   | +Inf  | NaN   |
| :-------- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **-Inf**  | `T`   | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   |
| **Neg**   | `F`   | `\|a\|==\|b\|` | `F`   | `F`   | `F`   | `F`   | `F`   |
| **-0**    | `F`   | `F`   | `T`   | `T`   | `F`   | `F`   | `F`   |
| **+0**    | `F`   | `F`   | `T`   | `T`   | `F`   | `F`   | `F`   |
| **Pos**   | `F`   | `F`   | `F`   | `F`   | `\|a\|==\|b\|` | `F`   | `F`   |
| **+Inf**  | `F`   | `F`   | `F`   | `F`   | `F`   | `T`   | `F`   |
| **NaN**   | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   | `F`   |
