# Transform Function

The `transform` function is a utility used in the optimized digit recurrence scripts (`radix4_qds_optimized.py` and `radix4_rds_optimized.py`). Its purpose is to resolve the overlap between adjacent digit selection intervals by choosing a specific boundary value.

In the context of digit selection, there is often a range of residual values (overlap) where either of two adjacent digits is valid. To construct a deterministic selection table (or logic), a specific threshold must be chosen within this overlap. This function selects thresholds that satisfy specific modulo arithmetic properties, which simplifies the resulting hardware logic (e.g., Karnaugh map minimization).

## Python Implementation

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

## Transformation Logic

The function operates on a pair $(x, y)$ representing the upper bound of the lower digit's valid interval and the lower bound of the higher digit's valid interval, respectively. The difference `diff = x - y` represents the size of the overlap minus one (conceptually).

### Modulo-4 Transformation (1 <= diff <= 2)

Used when the overlap allows for a choice within a small range.

| Input (x, y) | diff | Output (x', y') |
| :--- | :--- | :--- |
| (4a + 2, 4a + 1) | 1 | (4a + 1, 4a + 2) |
| (4a + 3, 4a + 2) | 1 | (4a + 3, 4a + 4) |
| (4a + 4, 4a + 3) | 1 | (4a + 3, 4a + 4) |
| (4a + 5, 4a + 4) | 1 | (4a + 3, 4a + 4) |
| (4a + 3, 4a + 1) | 2 | (4a + 3, 4a + 4) |
| (4a + 4, 4a + 2) | 2 | (4a + 3, 4a + 4) |
| (4a + 5, 4a + 3) | 2 | (4a + 3, 4a + 4) |
| (4a + 6, 4a + 4) | 2 | (4a + 3, 4a + 4) |

### Modulo-8 Transformation (3 <= diff <= 6)

Used when the overlap is larger, allowing for alignment to modulo-8 boundaries.

| Input (x, y) | diff | Output (x', y') |
| :--- | :--- | :--- |
| (8a + 4, 8a + 1) | 3 | (8a + 3, 8a + 4) |
| (8a + 5, 8a + 2) | 3 | (8a + 3, 8a + 4) |
| (8a + 6, 8a + 3) | 3 | (8a + 3, 8a + 4) |
| (8a + 7, 8a + 4) | 3 | (8a + 7, 8a + 8) |
| (8a + 8, 8a + 5) | 3 | (8a + 7, 8a + 8) |
| (8a + 9, 8a + 6) | 3 | (8a + 7, 8a + 8) |
| (8a + 10, 8a + 7) | 3 | (8a + 7, 8a + 8) |
| (8a + 11, 8a + 8) | 3 | (8a + 7, 8a + 8) |
| (8a + 5, 8a + 1) | 4 | (8a + 3, 8a + 4) |
| (8a + 6, 8a + 2) | 4 | (8a + 3, 8a + 4) |
| (8a + 7, 8a + 3) | 4 | (8a + 7, 8a + 8) |
| (8a + 8, 8a + 4) | 4 | (8a + 7, 8a + 8) |
| (8a + 9, 8a + 5) | 4 | (8a + 7, 8a + 8) |
| (8a + 10, 8a + 6) | 4 | (8a + 7, 8a + 8) |
| (8a + 11, 8a + 7) | 4 | (8a + 7, 8a + 8) |
| (8a + 12, 8a + 8) | 4 | (8a + 7, 8a + 8) |
| (8a + 6, 8a + 1) | 5 | (8a + 3, 8a + 4) |
| (8a + 7, 8a + 2) | 5 | (8a + 7, 8a + 8) |
| (8a + 8, 8a + 3) | 5 | (8a + 7, 8a + 8) |
| (8a + 9, 8a + 4) | 5 | (8a + 7, 8a + 8) |
| (8a + 10, 8a + 5) | 5 | (8a + 7, 8a + 8) |
| (8a + 11, 8a + 6) | 5 | (8a + 7, 8a + 8) |
| (8a + 12, 8a + 7) | 5 | (8a + 7, 8a + 8) |
| (8a + 13, 8a + 8) | 5 | (8a + 7, 8a + 8) |
| (8a + 7, 8a + 1) | 6 | (8a + 7, 8a + 8) |
| (8a + 8, 8a + 2) | 6 | (8a + 7, 8a + 8) |
| (8a + 9, 8a + 3) | 6 | (8a + 7, 8a + 8) |
| (8a + 10, 8a + 4) | 6 | (8a + 7, 8a + 8) |
| (8a + 11, 8a + 5) | 6 | (8a + 7, 8a + 8) |
| (8a + 12, 8a + 6) | 6 | (8a + 7, 8a + 8) |
| (8a + 13, 8a + 7) | 6 | (8a + 7, 8a + 8) |
| (8a + 14, 8a + 8) | 6 | (8a + 7, 8a + 8) |
