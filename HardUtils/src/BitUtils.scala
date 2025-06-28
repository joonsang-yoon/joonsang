package HardUtils

import chisel3._
import chisel3.util._

object Replicate {
  def apply(n: Int, bit: Bool): UInt = {
    Cat(Seq.fill(n)(bit))
  }
}

object LowMask {
  def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt = {
    require(topBound != bottomBound)
    val numInVals = BigInt(1) << in.getWidth
    if (topBound < bottomBound) {
      LowMask(~in, numInVals - 1 - topBound, numInVals - 1 - bottomBound)
    } else if (numInVals > 64 /* Empirical */ ) {
      // For simulation performance, we should avoid generating
      // exteremely wide shifters, so we divide and conquer.
      // Empirically, this does not impact synthesis QoR.
      val mid = numInVals >> 1
      val msb = in(in.getWidth - 1)
      val lsbs = in(in.getWidth - 2, 0)
      if (mid < topBound) {
        if (mid <= bottomBound) {
          Mux(msb, LowMask(lsbs, topBound - mid, bottomBound - mid), 0.U)
        } else {
          Mux(
            msb,
            Cat(LowMask(lsbs, topBound - mid, 0), Replicate((mid - bottomBound).toInt, true.B)),
            LowMask(lsbs, mid, bottomBound)
          )
        }
      } else {
        ~Mux(msb, 0.U, ~LowMask(lsbs, topBound, bottomBound))
      }
    } else {
      val shift = (-(BigInt(1) << numInVals.toInt)).S >> in
      Reverse(shift((numInVals - 1 - bottomBound).toInt, (numInVals - topBound).toInt))
    }
  }
}

object CountLeadingZeros {
  def apply(in: UInt): UInt = {
    val dataWidth = in.getWidth
    require(dataWidth >= 2, "dataWidth must be at least 2")

    val nextPow2 = 1 << log2Ceil(dataWidth)

    val paddedIn = Cat(in(dataWidth - 1, 1), true.B, 0.U((nextPow2 - dataWidth).W))

    clzPowerOfTwo(paddedIn)
  }

  private def clzPowerOfTwo(in: UInt): UInt = {
    val dataWidth = in.getWidth

    if (dataWidth == 2) {
      !in(1)
    } else {
      val halfWidth = dataWidth >> 1
      val upper = in(dataWidth - 1, halfWidth)
      val lower = in(halfWidth - 1, 0)

      val isUpperAllZeros = upper === 0.U

      val upperClz = clzPowerOfTwo(upper)
      val lowerClz = clzPowerOfTwo(lower)

      Cat(isUpperAllZeros, Mux(isUpperAllZeros, lowerClz, upperClz))
    }
  }
}

object OrReduceBy2 {
  def apply(in: UInt): UInt = {
    val reducedWidth = (in.getWidth + 1) >> 1
    val reducedVec = Wire(Vec(reducedWidth, Bool()))
    for (ix <- 0 until reducedWidth - 1) {
      reducedVec(ix) := in(ix * 2 + 1, ix * 2).orR
    }
    reducedVec(reducedWidth - 1) := in(in.getWidth - 1, (reducedWidth - 1) * 2).orR
    reducedVec.asUInt
  }
}

object OrReduceBy4 {
  def apply(in: UInt): UInt = {
    val reducedWidth = (in.getWidth + 3) >> 2
    val reducedVec = Wire(Vec(reducedWidth, Bool()))
    for (ix <- 0 until reducedWidth - 1) {
      reducedVec(ix) := in(ix * 4 + 3, ix * 4).orR
    }
    reducedVec(reducedWidth - 1) := in(in.getWidth - 1, (reducedWidth - 1) * 4).orR
    reducedVec.asUInt
  }
}
