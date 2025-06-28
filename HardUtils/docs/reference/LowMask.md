# `LowMask`: A Dynamic Bit Mask Generator

`LowMask` is a hardware utility in Chisel that generates a mask of consecutive `1`s starting from the least significant bit (LSB). The number of `1`s in the mask is determined dynamically by an input value.

This utility is particularly useful in floating-point units (FPUs) for tasks like:
-   **Rounding Logic**: Generating masks to identify "extra" bits that should be collapsed into a sticky bit.
-   **Alignment**: Creating masks for shifted significands.
-   **Range Comparisons**: Efficiently checking if a value falls within a specific bit-range.

## Function Signature

```scala
def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt
```

### Parameters

-   **`in` (`UInt`)**: The dynamic input signal. Its value determines how many `1`s are in the output mask.
-   **`topBound` (`BigInt`)**: A compile-time constant defining the upper boundary of the range.
-   **`bottomBound` (`BigInt`)**: A compile-time constant defining the lower boundary of the range.

### Constraints

-   **`topBound != bottomBound`**: The boundaries must be distinct.
-   **Output Width**: The width of the returned `UInt` is exactly `abs(topBound - bottomBound)`.

---

## Core Functionality

`LowMask` generates a "thermometer code" mask based on the relationship between `in` and the defined bounds.

### 1. Increasing Mask (`topBound > bottomBound`)

The number of `1`s grows as `in` increases.

-   **`in <= bottomBound`**: Output is all `0`s.
-   **`in >= topBound`**: Output is all `1`s.
-   **`bottomBound < in < topBound`**: Output has `in - bottomBound` ones at the LSBs.

**Example:** `LowMask(in, topBound = 4, bottomBound = 0)`

| `in` Value | Output (4-bit) | # of `1`s |
| :--------- | :------------- | :-------- |
| `0`        | `0000`         | 0         |
| `1`        | `0001`         | 1         |
| `2`        | `0011`         | 2         |
| `3`        | `0111`         | 3         |
| `4`        | `1111`         | 4         |

### 2. Decreasing Mask (`topBound < bottomBound`)

The number of `1`s shrinks as `in` increases.

-   **`in <= topBound`**: Output is all `1`s.
-   **`in >= bottomBound`**: Output is all `0`s.
-   **`topBound < in < bottomBound`**: Output has `bottomBound - in` ones at the LSBs.

**Example:** `LowMask(in, topBound = 0, bottomBound = 4)`

| `in` Value | Output (4-bit) | # of `1`s |
| :--------- | :------------- | :-------- |
| `0`        | `1111`         | 4         |
| `1`        | `0111`         | 3         |
| `2`        | `0011`         | 2         |
| `3`        | `0001`         | 1         |
| `4`        | `0000`         | 0         |

---

## Real-World Usage: Floating-Point Addition

In `AddRecFN.scala`, `LowMask` is used to generate a mask for "extra" bits during significand alignment. This mask helps determine if any bits shifted out are non-zero (the "sticky" bit).

```scala
// From AddRecFN.scala
val far_roundExtraMask = LowMask(
    alignDist(alignDistWidth - 1, 2), 
    (sigWidth + 5) >> 2, 
    0
)
```

Here, `alignDist` determines how far the smaller significand was shifted. `LowMask` creates a mask that covers the bits that were shifted out, allowing a simple bitwise AND and OR-reduction to compute the sticky bit efficiently.

---

## Implementation Details

`LowMask` is optimized for both simulation speed and hardware efficiency:

1.  **Small Inputs (≤ 64 bits)**: Uses a highly compact signed arithmetic shift and bit reversal.
    ```scala
    val shift = (-(BigInt(1) << numInVals.toInt)).S >> in
    Reverse(shift((numInVals - 1 - bottomBound).toInt, (numInVals - topBound).toInt))
    ```
2.  **Large Inputs (> 64 bits)**: Employs a **divide-and-conquer** approach. It recursively splits the problem into smaller masks and combines them with Muxes. This prevents the generation of extremely wide shifters which can slow down simulators like Verilator or VCS, while still synthesizing to efficient hardware.
3.  **Inversion**: Decreasing masks are implemented by recursively calling `LowMask` with bit-inverted inputs and transformed bounds.
