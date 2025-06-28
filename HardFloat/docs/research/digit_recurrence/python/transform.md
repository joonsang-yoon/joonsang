# Coordinate Transformation for Digit Recurrence

The `transform` function is a utility used in digit recurrence algorithms (such as radix-4 division and square root) to simplify the selection constants for Quotient Digit Selection (QDS) or Root Digit Selection (RDS).

## Overview

In digit recurrence algorithms, selection constants are used to determine the next digit based on the current residual and the divisor (or root). These constants often involve complex comparisons. The `transform` function maps an input pair of integers `(x, y)`, representing an overlap range `[y, x]` between two adjacent quotient digits, to a standardized pair `(x', y')` with a minimal difference of 1.

This transformation is crucial for:
1.  **Overlap Removal:** It resolves regions where multiple quotient digits are valid by picking a single, hardware-friendly boundary.
2.  **Standardizing Boundaries:** It ensures that the selection boundaries are consistent across different regions of the selection diagram.
3.  **Optimizing Hardware:** By mapping boundaries to specific patterns (like `(odd, even)` pairs), it simplifies the comparison logic in hardware.

## Logic

The function operates based on the difference `diff = x - y` between the input coordinates:

1.  **Minimal Separation (`diff = 0`):** If the inputs are identical, they are mapped to a pair with a difference of 1, effectively "splitting" the point into a small interval.
2.  **Range-Based Modulo Transformation:** For larger differences, the function uses a power-of-two modulus `M` (4, 8, or 16) determined by the magnitude of `diff`.
    -   It calculates a `base` and a `threshold` within that modulus.
    -   If the input `x` is below the `threshold`, it maps to a "low" pair.
    -   Otherwise, it maps to a "high" pair.
    -   In all cases, the output pair `(x', y')` satisfies `y' - x' = 1`.

## Implementation

```python
def transform(x: int, y: int) -> tuple[int, int]:
    diff = x - y

    if diff == 0:
        if x % 2 == 1:
            return (x, x + 1)
        else:
            return (x - 1, x)

    if 1 <= diff <= 2:
        M = 4
    elif 3 <= diff <= 6:
        M = 8
    elif 7 <= diff <= 14:
        M = 16
    else:
        raise ValueError(f"The transformation for diff={diff} is not defined.")

    # base = 4a or 8a
    base = (y - 1) // M * M

    # threshold = 4a + 3 or 8a + 7
    threshold = base + M - 1

    # xp_low = 4a + 1 or 8a + 3, yp_low = 4a + 2 or 8a + 4
    xp_low = base + M // 2 - 1
    yp_low = base + M // 2

    # xp_high = 4a + 3 or 8a + 7, yp_high = 4a + 4 or 8a + 8
    xp_high = base + M - 1
    yp_high = base + M

    if x < threshold:
        return (xp_low, yp_low)
    else:
        return (xp_high, yp_high)
```

## Transformation Rules

### Minimal Separation (`diff = 0`)

Maps a single point to an `(odd, even)` interval.

| Input `(x, y)` | Condition   | Output `(x', y')` |
|:---------------|:------------|:------------------|
| `(k, k)`       | `k` is odd  | `(k, k + 1)`      |
| `(k, k)`       | `k` is even | `(k - 1, k)`      |

### Modulo-4 Transformation (`1 <= diff <= 2`)

| Input `(x, y)`     | `diff` | Output `(x', y')`  |
|:-------------------|:-------|:-------------------|
| `(4a + 2, 4a + 1)` | `1`    | `(4a + 1, 4a + 2)` |
| `(4a + 3, 4a + 2)` | `1`    | `(4a + 3, 4a + 4)` |
| `(4a + 4, 4a + 3)` | `1`    | `(4a + 3, 4a + 4)` |
| `(4a + 5, 4a + 4)` | `1`    | `(4a + 3, 4a + 4)` |
| `(4a + 3, 4a + 1)` | `2`    | `(4a + 3, 4a + 4)` |
| `(4a + 4, 4a + 2)` | `2`    | `(4a + 3, 4a + 4)` |
| `(4a + 5, 4a + 3)` | `2`    | `(4a + 3, 4a + 4)` |
| `(4a + 6, 4a + 4)` | `2`    | `(4a + 3, 4a + 4)` |

### Modulo-8 Transformation (`3 <= diff <= 6`)

| Input `(x, y)`      | `diff` | Output `(x', y')`  |
|:--------------------|:-------|:-------------------|
| `(8a + 4, 8a + 1)`  | `3`    | `(8a + 3, 8a + 4)` |
| `(8a + 5, 8a + 2)`  | `3`    | `(8a + 3, 8a + 4)` |
| `(8a + 6, 8a + 3)`  | `3`    | `(8a + 3, 8a + 4)` |
| `(8a + 7, 8a + 4)`  | `3`    | `(8a + 7, 8a + 8)` |
| `(8a + 8, 8a + 5)`  | `3`    | `(8a + 7, 8a + 8)` |
| `(8a + 9, 8a + 6)`  | `3`    | `(8a + 7, 8a + 8)` |
| `(8a + 10, 8a + 7)` | `3`    | `(8a + 7, 8a + 8)` |
| `(8a + 11, 8a + 8)` | `3`    | `(8a + 7, 8a + 8)` |
| `(8a + 5, 8a + 1)`  | `4`    | `(8a + 3, 8a + 4)` |
| `(8a + 6, 8a + 2)`  | `4`    | `(8a + 3, 8a + 4)` |
| `(8a + 7, 8a + 3)`  | `4`    | `(8a + 7, 8a + 8)` |
| `(8a + 8, 8a + 4)`  | `4`    | `(8a + 7, 8a + 8)` |
| `(8a + 9, 8a + 5)`  | `4`    | `(8a + 7, 8a + 8)` |
| `(8a + 10, 8a + 6)` | `4`    | `(8a + 7, 8a + 8)` |
| `(8a + 11, 8a + 7)` | `4`    | `(8a + 7, 8a + 8)` |
| `(8a + 12, 8a + 8)` | `4`    | `(8a + 7, 8a + 8)` |
| `(8a + 6, 8a + 1)`  | `5`    | `(8a + 3, 8a + 4)` |
| `(8a + 7, 8a + 2)`  | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 8, 8a + 3)`  | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 9, 8a + 4)`  | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 10, 8a + 5)` | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 11, 8a + 6)` | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 12, 8a + 7)` | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 13, 8a + 8)` | `5`    | `(8a + 7, 8a + 8)` |
| `(8a + 7, 8a + 1)`  | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 8, 8a + 2)`  | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 9, 8a + 3)`  | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 10, 8a + 4)` | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 11, 8a + 5)` | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 12, 8a + 6)` | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 13, 8a + 7)` | `6`    | `(8a + 7, 8a + 8)` |
| `(8a + 14, 8a + 8)` | `6`    | `(8a + 7, 8a + 8)` |

## Structure Summary

| `diff` Range | `M`  | `Base` | `Threshold` | Low Pair             | High Pair              |
|:-------------|:-----|:-------|:------------|:---------------------|:-----------------------|
| `0`          | —    | —      | —           | `(x, x + 1)` if odd  | `(x - 1, x)` if even   |
| `1 to 2`     | `4`  | `4a`   | `4a + 3`    | `(4a + 1, 4a + 2)`   | `(4a + 3, 4a + 4)`     |
| `3 to 6`     | `8`  | `8a`   | `8a + 7`    | `(8a + 3, 8a + 4)`   | `(8a + 7, 8a + 8)`     |
| `7 to 14`    | `16` | `16a`  | `16a + 15`  | `(16a + 7, 16a + 8)` | `(16a + 15, 16a + 16)` |

## Key Properties

1.  **Unit Interval:** The output always satisfies `y' - x' = 1`.
2.  **Odd-Even Alignment:** The lower boundary `x'` is always **odd**, and the upper boundary `y'` is always **even**.
3.  **Power-of-Two Alignment:** The boundaries are aligned to `M/2` or `M` multiples, which simplifies bitwise comparisons in hardware.
4.  **Hysteresis/Stability:** By mapping a range of overlaps to a single boundary, the transformation provides a stable selection point that is less sensitive to small variations in the input coordinates.
