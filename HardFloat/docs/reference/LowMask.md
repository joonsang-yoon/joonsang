# `LowMask`: A Dynamic Bit Mask Generator

`LowMask` is a hardware utility in Chisel that generates a mask of consecutive `1`s from the least significant bit (LSB). The number of `1`s in the mask is determined dynamically by an input value, making it a powerful tool for range comparisons, priority encoding, and creating thermometer-style representations in hardware.

## Function Signature

```scala
def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt
```

## Parameters

-   **`in` (`UInt`)**: The input signal. The value of this signal determines the width of the generated mask of `1`s.
-   **`topBound` (`BigInt`)**: A compile-time constant that defines the upper boundary of the input range.
-   **`bottomBound` (`BigInt`)**: A compile-time constant that defines the lower boundary of the input range.

## Return Value

-   **`UInt`**: The output mask. The width of this `UInt` is equal to the absolute difference between `topBound` and `bottomBound` (`abs(topBound - bottomBound)`).

## Core Functionality

`LowMask` compares the input `in` to the range defined by `topBound` and `bottomBound` and generates a mask accordingly.

### Case 1: Increasing Mask (`topBound > bottomBound`)

This is the most common use case. The number of `1`s in the mask grows as the input `in` increases.

-   **`in <= bottomBound`**: The input is below or at the bottom of the range. The output is all `0`s.
-   **`in >= topBound`**: The input is at or above the top of the range. The output is all `1`s (fully saturated).
-   **`bottomBound < in < topBound`**: The input is within the range. The output is a mask with `in - bottomBound` ones.

#### Example: Increasing Mask

Let's create a 4-bit mask that activates as `in` goes from `x` to `x + 4`.

**Code:**
```scala
// Assume x is a compile-time constant
io.out := LowMask(io.in, topBound = x + 4, bottomBound = x)
```

**Behavior:**

| `io.in` Value | `io.out` (4-bit Binary) | Number of `1`s |
|:--------------|:------------------------|:---------------|
| `x - 1`       | `0000`                  | 0              |
| `x`           | `0000`                  | 0              |
| `x + 1`       | `0001`                  | 1              |
| `x + 2`       | `0011`                  | 2              |
| `x + 3`       | `0111`                  | 3              |
| `x + 4`       | `1111`                  | 4              |
| `x + 5`       | `1111`                  | 4              |

### Case 2: Decreasing Mask (`topBound < bottomBound`)

In this configuration, the behavior is inverted. The number of `1`s in the mask *shrinks* as the input `in` increases.

-   **`in <= topBound`**: The input is at or below the top of the range. The output is all `1`s.
-   **`in >= bottomBound`**: The input is at or above the bottom of the range. The output is all `0`s.
-   **`topBound < in < bottomBound`**: The input is within the range. The output is a mask with `bottomBound - in` ones.

#### Example: Decreasing Mask

Let's create a 4-bit mask that deactivates as `in` goes from `x` to `x + 4`.

**Code:**
```scala
// Assume x is a compile-time constant
io.out := LowMask(io.in, topBound = x, bottomBound = x + 4)
```

**Behavior:**

| `io.in` Value | `io.out` (4-bit Binary) | Number of `1`s |
|:--------------|:------------------------|:---------------|
| `x - 1`       | `1111`                  | 4              |
| `x`           | `1111`                  | 4              |
| `x + 1`       | `0111`                  | 3              |
| `x + 2`       | `0011`                  | 2              |
| `x + 3`       | `0001`                  | 1              |
| `x + 4`       | `0000`                  | 0              |
| `x + 5`       | `0000`                  | 0              |

## Implementation Insights

`LowMask` employs several clever implementation strategies depending on the configuration.

1.  **Inverted Range (`topBound < bottomBound`)**: The decreasing mask case is elegantly handled by inverting the problem. It recursively calls itself with an inverted input (`~in`) and transformed bounds, effectively turning it into an increasing mask problem that can be solved by the other methods.

2.  **Large Input Width (`numInVals > 64`)**: For very wide inputs, a direct bit-shift implementation can be slow in simulation. `LowMask` uses a divide-and-conquer approach. It splits the input into its most significant bit (MSB) and lower bits, recursively calls `LowMask` on the smaller parts, and then uses a `Mux` controlled by the MSB to combine the results. This hierarchical structure is much more efficient for simulation and synthesizes to the same efficient hardware.

3.  **Standard Case (Optimized Bit-Shift)**: For inputs up to 64 bits wide, `LowMask` uses a highly efficient and compact implementation based on signed arithmetic shifts.

    ```scala
    val shift = (-(BigInt(1) << numInVals.toInt)).S >> in
    Reverse(shift((numInVals - 1 - bottomBound).toInt, (numInVals - topBound).toInt))
    ```

    -   `(-(BigInt(1) << numInVals.toInt)).S`: This creates a signed number with `numInVals` ones at the MSBs (e.g., `...1111_0000` for `numInVals=4`).
    -   `>> in`: An arithmetic right shift is performed. Because the number is negative, `1`s are shifted in from the left. This creates a mask of `in` ones at the MSBs.
    -   `Reverse(...)`: The result is reversed to move the mask to the LSBs.
    -   `shift(...)`: A final bit extraction selects the correct window of bits corresponding to the `topBound` and `bottomBound`, producing the final mask of the correct width.
