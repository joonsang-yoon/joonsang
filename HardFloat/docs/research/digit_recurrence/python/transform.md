# `transform` Function

The `transform` function is a key utility used in the optimized digit recurrence algorithms for both division (`radix4_qds_optimized.py`) and square root (`radix4_rds_optimized.py`). Its primary purpose is to resolve overlaps between the selection regions for different result digits.

In digit recurrence algorithms, each possible result digit corresponds to a specific range of the estimated partial remainder (or residual). In a basic implementation, these ranges overlap. To create a deterministic hardware implementation (e.g., a lookup table), these overlaps must be eliminated. The `remove_overlaps` function iterates through the boundaries of these regions and calls `transform` to adjust them, creating a clean, non-overlapping partition.

The function takes two integer inputs, `x` and `y`, which represent the upper boundary of a selection region and the lower boundary of the adjacent, higher selection region, respectively. It returns a new pair of boundaries, `(x', y')`, that are guaranteed not to overlap.

## Function Definition

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

## Detailed Transformation Rules

The behavior of the function depends on `diff = x - y`. The following tables illustrate the transformation for different values of `diff`.

### `diff = 0`

When the boundaries are equal, the function nudges them apart to create a minimal separation.

| Input `(x, y)` | Condition | Output `(x', y')` |
| :------------- | :-------- | :---------------- |
| `(k, k)`       | `k` is odd  | `(k, k + 1)`      |
| `(k, k)`       | `k` is even | `(k - 1, k)`      |

**Examples:**
| `x`, `y`   | `x'`, `y'` |
| :--------- | :--------- |
| `2a+1`, `2a+1` | `2a+1`, `2a+2` |
| `2a+2`, `2a+2` | `2a+1`, `2a+2` |

### `1 <= diff <= 2` (`M=4`)

For small positive differences, the transformation maps the boundaries to one of two standard pairs within a mod-4 interval.

| Input `(x, y)` | `diff` | Output `(x', y')` |
| :------------- | :----: | :---------------- |
| `(4a+2, 4a+1)` | 1      | `(4a+1, 4a+2)`    |
| `(4a+3, 4a+2)` | 1      | `(4a+3, 4a+4)`    |
| `(4a+4, 4a+3)` | 1      | `(4a+3, 4a+4)`    |
| `(4a+5, 4a+4)` | 1      | `(4a+3, 4a+4)`    |
| `(4a+3, 4a+1)` | 2      | `(4a+3, 4a+4)`    |
| `(4a+4, 4a+2)` | 2      | `(4a+3, 4a+4)`    |
| `(4a+5, 4a+3)` | 2      | `(4a+3, 4a+4)`    |
| `(4a+6, 4a+4)` | 2      | `(4a+3, 4a+4)`    |

### `3 <= diff <= 6` (`M=8`)

For larger differences, the logic extends to mod-8 intervals.

| Input `(x, y)` | `diff` | Output `(x', y')` |
| :------------- | :----: | :---------------- |
| `(8a+4, 8a+1)` | 3      | `(8a+3, 8a+4)`    |
| `(8a+5, 8a+2)` | 3      | `(8a+3, 8a+4)`    |
| `(8a+6, 8a+3)` | 3      | `(8a+3, 8a+4)`    |
| `(8a+7, 8a+4)` | 3      | `(8a+7, 8a+8)`    |
| `(8a+8, 8a+5)` | 3      | `(8a+7, 8a+8)`    |
| `(8a+9, 8a+6)` | 3      | `(8a+7, 8a+8)`    |
| `(8a+10, 8a+7)`| 3      | `(8a+7, 8a+8)`    |
| `(8a+11, 8a+8)`| 3      | `(8a+7, 8a+8)`    |
| `(8a+5, 8a+1)` | 4      | `(8a+3, 8a+4)`    |
| `(8a+6, 8a+2)` | 4      | `(8a+3, 8a+4)`    |
| `(8a+7, 8a+3)` | 4      | `(8a+7, 8a+8)`    |
| `(8a+8, 8a+4)` | 4      | `(8a+7, 8a+8)`    |
| `(8a+9, 8a+5)` | 4      | `(8a+7, 8a+8)`    |
| `(8a+10, 8a+6)`| 4      | `(8a+7, 8a+8)`    |
| `(8a+11, 8a+7)`| 4      | `(8a+7, 8a+8)`    |
| `(8a+12, 8a+8)`| 4      | `(8a+7, 8a+8)`    |
| `(8a+6, 8a+1)` | 5      | `(8a+3, 8a+4)`    |
| `(8a+7, 8a+2)` | 5      | `(8a+7, 8a+8)`    |
| `(8a+8, 8a+3)` | 5      | `(8a+7, 8a+8)`    |
| `(8a+9, 8a+4)` | 5      | `(8a+7, 8a+8)`    |
| `(8a+10, 8a+5)`| 5      | `(8a+7, 8a+8)`    |
| `(8a+11, 8a+6)`| 5      | `(8a+7, 8a+8)`    |
| `(8a+12, 8a+7)`| 5      | `(8a+7, 8a+8)`    |
| `(8a+13, 8a+8)`| 5      | `(8a+7, 8a+8)`    |
| `(8a+7, 8a+1)` | 6      | `(8a+7, 8a+8)`    |
| `(8a+8, 8a+2)` | 6      | `(8a+7, 8a+8)`    |
| `(8a+9, 8a+3)` | 6      | `(8a+7, 8a+8)`    |
| `(8a+10, 8a+4)`| 6      | `(8a+7, 8a+8)`    |
| `(8a+11, 8a+5)`| 6      | `(8a+7, 8a+8)`    |
| `(8a+12, 8a+6)`| 6      | `(8a+7, 8a+8)`    |
| `(8a+13, 8a+7)`| 6      | `(8a+7, 8a+8)`    |
| `(8a+14, 8a+8)`| 6      | `(8a+7, 8a+8)`    |
