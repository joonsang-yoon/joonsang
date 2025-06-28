# Deterministic Overlap Resolution for the Optimized Radix-4 Selectors

`transform()` is the small helper that turns one overlapping pair of adjacent integer intervals into one deterministic, gap-free cut. Both `division/radix4_qds_optimized.py` and `square_root/radix4_rds_optimized.py` call it through `remove_overlaps()`.

The basic selector tables intentionally keep overlap because multiple digits may be correct over the same residual range. The optimized selectors keep the same correctness but replace that freedom with one reproducible choice per column. `transform()` is the rule that makes that choice.

## Caller contract

`transform()` is not called on arbitrary endpoints. Before `remove_overlaps()` walks a selector column, the intervals are ordered from **higher `T` to lower `T`**. For one adjacent pair:

- interval `i` is the **top** interval
- interval `i + 1` is the **bottom** interval
- `y = min_t_list[i]` is the **lower edge of the top interval**
- `x = max_t_list[i + 1]` is the **upper edge of the bottom interval**

So the geometry is:

```text
before
  top interval:     [ y ..................... top_max ]
  bottom interval:  [ bottom_min ............. x ]

  overlap or touch  <=>  y <= x
  already disjoint  <=>  x + 1 == y
```

The caller treats `diff = x - y` as follows:

- `diff == -1`: already disjoint, so no change
- `diff >= 0`: overlap or single-point touch, so call `transform(x, y)`

After the returned pair is written back,

```python
max_t_list[i + 1] = x_prime
min_t_list[i] = y_prime
```

the two intervals become

```text
after
  top interval:     [ y' .................... top_max ]
  bottom interval:  [ bottom_min ............. x' ]
```

with a deliberate adjacent-integer seam:

```text
x' < y'    and in fact    y' = x' + 1
```

Because the intervals are inclusive integer ranges, that removes the overlap without introducing a lattice hole.

## Why the ordering matters

The arguments are **not symmetric**.

- `x` must be the upper edge of the lower interval
- `y` must be the lower edge of the upper interval

That is why the optimized scripts first sort the digit labels so the interval list is monotone in `T`:

- division, positive `D`: descending quotient digits
- division, negative `D`: ascending quotient digits
- square root: descending root digits

In each case, index `i` is above index `i + 1` on the plot.

A useful consequence is that the one-pass sweep in `remove_overlaps()` is safe. Each call only tightens the two **inner** boundaries of one pair:

- the top interval's lower edge moves upward or stays put
- the bottom interval's upper edge moves downward or stays put

The next loop iteration uses interval `i + 1` together with the interval below it, but it consults interval `i + 1`'s **lower** edge, which the previous step did not modify. So the local pairwise rewrite composes cleanly across the whole column.

## Exact implementation

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

    base = (y - 1) // M * M
    threshold = base + M - 1

    xp_low = base + M // 2 - 1
    yp_low = base + M // 2

    xp_high = base + M - 1
    yp_high = base + M

    if x < threshold:
        return (xp_low, yp_low)
    else:
        return (xp_high, yp_high)
```

## How the rule works

### 1. Measure the overlap width

The helper begins with

```python
diff = x - y
```

Under the caller contract:

- `diff == 0` means the two intervals meet at one shared lattice point
- `diff > 0` means the overlap is the inclusive integer range `[y, x]`

### 2. Handle the single-point case by parity

When `diff == 0`, the helper chooses between the two adjacent seams around that shared point:

- odd `x`  -> `(x, x + 1)`
- even `x` -> `(x - 1, x)`

So the tie is broken by a regular odd/even pattern rather than by an operation-specific special case. This works for negative integers too, because Python's parity test via `x % 2` is consistent there as well.

### 3. Bucket wider overlaps by the smallest supported power-of-two block

For nontrivial overlaps, `transform()` chooses the smallest alignment bucket that can host one of its predefined seams:

| `diff` | `M` | Candidate seam family |
|---|---:|---|
| `1..2` | 4  | `4a + 1 | 4a + 2` or `4a + 3 | 4a + 4` |
| `3..6` | 8  | `8a + 3 | 8a + 4` or `8a + 7 | 8a + 8` |
| `7..14` | 16 | `16a + 7 | 16a + 8` or `16a + 15 | 16a + 16` |

The point is not to find a mathematically unique split. The point is to snap the boundary onto a very small set of repeated modulo classes.

### 4. Find the aligned `M`-cell that contains `y`

```python
base = (y - 1) // M * M
```

This defines the unique cell

```text
{ base + 1, base + 2, ..., base + M }
```

that contains `y`, i.e.

```text
base + 1 <= y <= base + M
```

The `y - 1` detail matters. It makes the cell boundaries behave the same way at the top edge of a block and for negative coordinates. For example, if `y = 8` and `M = 8`, this formula places `y` in the cell `{1, ..., 8}` rather than the next cell `{9, ..., 16}`.

### 5. Define the only two admissible seams inside that cell

Once the cell is fixed, the helper considers exactly two adjacent-integer cuts:

```python
xp_low  = base + M // 2 - 1
yp_low  = base + M // 2

xp_high = base + M - 1
yp_high = base + M
```

So every nontrivial decision is between:

- the **middle seam** of the cell
- the **top seam** of the cell

### 6. Choose the middle seam unless `x` reaches the top seam

```python
threshold = base + M - 1

if x < threshold:
    return (xp_low, yp_low)
else:
    return (xp_high, yp_high)
```

Interpretation:

- if the lower interval does **not** reach the top seam of the cell, use the middle seam
- otherwise, use the top seam

That is the entire policy.

## Minimal caller pattern

The optimized scripts use the helper through `remove_overlaps()` in this exact shape:

```python
x = max_t_list[i + 1]   # upper edge of bottom interval
y = min_t_list[i]       # lower edge of top interval
diff = x - y

if diff == -1:
    continue            # already disjoint

new_x, new_y = transform(x, y)
max_t_list[i + 1] = new_x
min_t_list[i] = new_y
```

So `transform()` is only responsible for the touching or overlapping cases. The already-disjoint case is filtered out by the caller.

## What the helper guarantees

For every supported input, `transform()` guarantees all of the following.

### Deterministic

The same `(x, y)` always produces the same `(x', y')`.

### Local tightening only

The inner boundaries only move inward:

```text
x' <= x    and    y' >= y
```

So the helper never expands either interval; it only tightens the pair around their shared region.

### Gap-free and non-overlapping

The returned pair always satisfies

```text
x' < y'    and    y' = x' + 1
```

So after the caller writes the new edges back, the two intervals abut exactly on the integer lattice: no overlap remains and no integer point between them is left uncovered.

### Alignment-friendly

The new seam always lands on one of a very small set of residue classes:

- modulo 4
- modulo 8
- modulo 16

That regularity is the whole point of the transformation.

### Column-local

The rule depends only on the two touching edges of one adjacent pair. It does not inspect the rest of the selector column and does not require a global optimization pass.

## What the helper does *not* optimize

Two caveats are easy to miss.

### It does not try to split the overlap evenly

The chosen seam may assign almost all of the old overlap to one side. Regularity matters more than symmetry.

### The new seam need not lie entirely inside the old overlap

The helper is allowed to slide to the nearest permitted modulo-aligned seam in the chosen cell, even if that moves one endpoint just outside the original shared range.

For example:

```python
transform(-19, -20) -> (-21, -20)
```

The old shared region is `[-20, -19]`, but the new seam is `-21 | -20`. That assigns the former overlap entirely to the top interval. The result is still correct because the final partition remains deterministic and gap-free.

## Worked examples

### Example 1: single shared point

```python
transform(24, 24) -> (23, 24)
transform(11, 11) -> (11, 12)
```

- even shared points move left
- odd shared points stay on the lower interval's side

### Example 2: overlap resolved in an 8-cell

```python
transform(10, 7) -> (7, 8)
```

Reason:

- `diff = 3`, so `M = 8`
- `base = 0`, so the cell is `{1, ..., 8}`
- candidate seams are `3 | 4` and `7 | 8`
- `x = 10` reaches beyond the cell's top-seam threshold `7`, so the helper chooses `7 | 8`

### Example 3: top seam of a 16-cell

```python
transform(15, 8) -> (15, 16)
```

Reason:

- `diff = 7`, so `M = 16`
- `base = 0`, so the cell is `{1, ..., 16}`
- candidate seams are `7 | 8` and `15 | 16`
- since `x = 15` reaches the threshold, the top seam is selected

### Example 4: negative coordinates

```python
transform(-4, -7) -> (-5, -4)
```

Reason:

- `diff = 3`, so `M = 8`
- `base = -8`, so the cell is `{-7, ..., 0}`
- candidate seams are `-5 | -4` and `-1 | 0`
- `x = -4` does not reach the threshold `-1`, so the middle seam is chosen

This is exactly why

```python
base = (y - 1) // M * M
```

is preferable to ad-hoc rounding: it preserves the same modulo rule on both positive and negative coordinates.

## Supported inputs and failure mode

The helper accepts these overlap-width classes:

- `diff == 0`
- `1 <= diff <= 2`
- `3 <= diff <= 6`
- `7 <= diff <= 14`

Anything else raises

```python
ValueError(f"The transformation for diff={diff} is not defined.")
```

So:

- `diff == -1` is the caller's already-disjoint case
- `diff < -1` violates the intended caller contract
- `diff > 14` means the bucket schedule must be extended

In the **current** radix-4 selector tables used by these scripts, adjacent pairs appear to exercise `diff` values only in `0..7`. So the `8..14` portion of the last bucket is extra headroom in the helper rather than a case the present tables visibly use.

## Why this policy is useful

The basic selector tables preserve every mathematically valid overlap. The optimized tables do something more implementation-oriented: they collapse each adjacent overlap into one fixed seam chosen from a small family of repeated modulo patterns.

That does **not** change which digit sequences are correct. It changes how easy those regions are to encode. Regular modulo-aligned seams are much easier to simplify into compact hardware logic than arbitrary per-column hand-picked boundaries.

In one sentence: `transform()` turns “many legal overlap choices” into “one repeatable, hardware-friendly choice.”
