# LowMask

The `LowMask` object generates a bitmask based on an input index `in` and specified boundary values. It is commonly used to generate masks for alignment, rounding, or masking operations where the number of set bits depends on a dynamic value.

## Definition

```scala
object LowMask {
  def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt
}
```

### Parameters

*   **`in`**: A `UInt` representing the input index or shift amount.
*   **`topBound`**: The upper bound of the range.
*   **`bottomBound`**: The lower bound of the range.

The width of the output `UInt` is `abs(topBound - bottomBound)`.
**Note:** `topBound` must not equal `bottomBound`.

## Behavior

The behavior depends on the relationship between `topBound` and `bottomBound`.

### Increasing Mask (`topBound > bottomBound`)

Generates a mask of `1`s starting from the LSB, where the number of `1`s grows as `in` increases.

*   **Width**: `topBound - bottomBound` bits.
*   **Logic**:
    *   **`in <= bottomBound`**: Output is all `0`s.
    *   **`in >= topBound`**: Output is all `1`s.
    *   **`bottomBound < in < topBound`**: Output has `in - bottomBound` ones at the LSBs.

**Example:** `LowMask(in, topBound=5, bottomBound=2)`
Width = 3 bits.

| `in` | Output (Binary) | Description |
| :--- | :--- | :--- |
| 0 | `000` | `in <= 2` |
| 1 | `000` | `in <= 2` |
| 2 | `000` | `in <= 2` |
| 3 | `001` | `in - 2 = 1` bit set |
| 4 | `011` | `in - 2 = 2` bits set |
| 5 | `111` | `in >= 5` |
| 6 | `111` | `in >= 5` |

### Decreasing Mask (`topBound < bottomBound`)

Generates a mask of `1`s starting from the LSB, where the number of `1`s shrinks as `in` increases.

*   **Width**: `bottomBound - topBound` bits.
*   **Logic**:
    *   **`in <= topBound`**: Output is all `1`s.
    *   **`in >= bottomBound`**: Output is all `0`s.
    *   **`topBound < in < bottomBound`**: Output has `bottomBound - in` ones at the LSBs.

**Example:** `LowMask(in, topBound=2, bottomBound=5)`
Width = 3 bits.

| `in` | Output (Binary) | Description |
| :--- | :--- | :--- |
| 0 | `111` | `in <= 2` |
| 1 | `111` | `in <= 2` |
| 2 | `111` | `in <= 2` |
| 3 | `011` | `5 - in = 2` bits set |
| 4 | `001` | `5 - in = 1` bit set |
| 5 | `000` | `in >= 5` |
| 6 | `000` | `in >= 5` |

## Implementation Details

The utility is optimized for synthesis. For small widths, it uses efficient shift-and-reverse operations. For larger widths (e.g., > 64 bits), it employs a divide-and-conquer strategy to avoid generating excessively wide shifters, ensuring good Quality of Results (QoR).
