# LowMask

`LowMask` returns a **contiguous run of low-order `1`s** whose length is controlled by `in`.

Only the **length** changes. The result is always packed against bit 0, and it saturates cleanly at both ends.

That makes `LowMask` a good fit for arithmetic datapaths that need “the lowest _N_ groups” without building extra compare-and-merge logic. In this codebase it often appears next to `OrReduceBy2(...)` or `OrReduceBy4(...)`, where a shift distance or exponent decides how many reduced low groups should participate.

## Signature

```scala
object LowMask {
  def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt
}
```

## What the arguments mean

- `in` is the runtime control input.
- `topBound` and `bottomBound` are **elaboration-time thresholds** in the value space of `in`.
- The output width is `abs(topBound - bottomBound)` bits.
- `topBound != bottomBound` is required.

The most important point is this:

> `topBound` and `bottomBound` are **not** output bit indices.
>
> They describe the input interval over which the mask population ramps from one extreme to the other.

Because the bounds are `BigInt`s, the mask width and the ramp span are compile-time constants. Only `in` is dynamic.

## Intended bound range

Let:

```scala
val numInVals = 1 << in.getWidth
```

`LowMask` is meant to be used with thresholds inside that input domain:

```scala
0 <= min(topBound, bottomBound)
max(topBound, bottomBound) <= numInVals
```

The implementation only explicitly requires `topBound != bottomBound`, but the returned slices and replicated regions are defined with that threshold domain in mind. All in-tree uses follow it.

## Exact behavior

A precise software model is:

```scala
def lowMaskModel(in: Int, topBound: Int, bottomBound: Int): BigInt = {
  require(topBound != bottomBound)

  val width = (topBound - bottomBound).abs
  val ones =
    if (topBound > bottomBound) {
      (in - bottomBound).max(0).min(width)
    } else {
      (bottomBound - in).max(0).min(width)
    }

  if (ones == 0) BigInt(0) else (BigInt(1) << ones) - 1
}
```

So the result always has one of these forms:

```text
0000...
0001...
0011...
0111...
1111...
```

Equivalently:

- `PopCount(out)` is the clamped number of active low bits.
- The mask never has holes.
- The mask is monotone with `in`:
  - nondecreasing when `topBound > bottomBound`
  - nonincreasing when `topBound < bottomBound`

A compact way to remember the primitive is:

> `LowMask` answers “how many low bits should be on right now?” and then returns exactly that many `1`s packed against bit 0.

## Growing mask: `topBound > bottomBound`

This is the form used when the active low region should **grow** as `in` increases.

Let:

```scala
val width = topBound - bottomBound
```

Then:

- `in <= bottomBound` gives all `0`s
- `in >= topBound` gives all `1`s
- otherwise, exactly `in - bottomBound` low bits are set

Equivalent expression:

```scala
out = (BigInt(1) << clamp(in - bottomBound, 0, width)) - 1
```

Bit-level rule for `0 <= i < width`:

```scala
out(i) == 1  iff  bottomBound + i < in
```

### Example

`LowMask(in, topBound = 5, bottomBound = 2)` returns a 3-bit result:

| `in` | `out` |
|---:|:---|
| 0 | `000` |
| 1 | `000` |
| 2 | `000` |
| 3 | `001` |
| 4 | `011` |
| 5 | `111` |
| 6 | `111` |

You can read the same table as a sequence of threshold crossings:

- crossing `3` turns on bit 0
- crossing `4` turns on bit 1
- crossing `5` turns on bit 2

## Shrinking mask: `topBound < bottomBound`

This is the mirrored form. The active low region **shrinks** as `in` increases.

Let:

```scala
val width = bottomBound - topBound
```

Then:

- `in <= topBound` gives all `1`s
- `in >= bottomBound` gives all `0`s
- otherwise, exactly `bottomBound - in` low bits are set

Equivalent expression:

```scala
out = (BigInt(1) << clamp(bottomBound - in, 0, width)) - 1
```

Bit-level rule for `0 <= i < width`:

```scala
out(i) == 1  iff  in < bottomBound - i
```

### Example

`LowMask(in, topBound = 2, bottomBound = 5)` returns a 3-bit result:

| `in` | `out` |
|---:|:---|
| 0 | `111` |
| 1 | `111` |
| 2 | `111` |
| 3 | `011` |
| 4 | `001` |
| 5 | `000` |
| 6 | `000` |

This is the exact reverse ramp of the previous example.

## Mirror identity

The descending case is not a different primitive. It is the same ramp reflected across the fixed-width input domain.

If:

```scala
val numInVals = 1 << in.getWidth
```

then the implementation uses the exact identity:

```scala
LowMask(in, topBound, bottomBound) ==
  LowMask(~in, numInVals - 1 - topBound, numInVals - 1 - bottomBound)
```

for the `topBound < bottomBound` case.

That is why the two modes are perfectly symmetric.

## Why it shows up next to `OrReduceBy2` and `OrReduceBy4`

`LowMask` is especially useful when a wide bit-vector has already been reduced into 2-bit or 4-bit groups and the datapath only wants the lowest active groups.

### Sticky-style selection during alignment

```scala
OrReduceBy4(Cat(far_sigSmaller, 0.U(2.W))) &
  LowMask(alignDist(alignDistWidth - 1, 2), (sigWidth + 5) >> 2, 0)
```

Here the mask is in the **growing** form. As `alignDist` grows, more low reduced groups feed the sticky-bit path.

### Reduced-group selection in fused multiply-add alignment

```scala
OrReduceBy4(
  Cat(rawC.sig(sigWidth - 1 - ((sigSumWidth - 1) & 3), 0), 0.U(((sigSumWidth - sigWidth - 1) & 3).W))
) & LowMask(
  cAlignDist(log2Ceil(sigSumWidth) - 1, 2),
  (sigSumWidth - 1) >> 2,
  (sigSumWidth - sigWidth - 1) >> 2
)
```

This is the same pattern: choose the low reduced groups that lie below the current alignment boundary.

### Rounding-mask construction

```scala
Cat(
  LowMask(sAdjustedExp(outExpWidth, 0), outMinNormExp - outSigWidth - 1, outMinNormExp) |
    doShiftSigDown1,
  3.U(2.W)
)
```

This one uses the **shrinking** form. As the adjusted exponent approaches the normal range, the active low rounding region gets smaller.

## Implementation sketch

The source has three semantic pieces.

### 1. Descending ramps are implemented by mirroring the input space

The `topBound < bottomBound` branch immediately rewrites the problem into the ascending form using the mirror identity above.

### 2. Small domains use a compact shift-based construction

When:

```scala
(1 << in.getWidth) <= 64
```

`LowMask` builds the mask from a signed constant and an arithmetic shift, then slices out the requested ramp window.

This is the compact path.

### 3. Large domains use divide-and-conquer

When the input domain is larger than `64`, the implementation recursively splits on the MSB of `in` instead of generating a very wide dynamic shifter.

That path:

- splits the domain at `mid = numInVals >> 1`
- recurses on the low bits of `in`
- stitches the final mask together with `Mux`, `Cat`, and replicated `1`s

The `64` cutover is an implementation choice for simulation performance, not a semantic boundary.

## Takeaway

`LowMask` is a small helper, but its contract is crisp:

- the output is always a contiguous low-order run of `1`s
- the output width is `abs(topBound - bottomBound)`
- the number of `1`s changes monotonically with `in`
- the result saturates at both ends of the ramp

If you keep one rule in your head, keep this one:

> `LowMask` computes how many low bits should be enabled for the current `in`, clamps that count to the ramp width, and returns that many low `1`s.
