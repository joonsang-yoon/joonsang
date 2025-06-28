package HardUtils

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

object Replicate {
  def apply(n: Int, bit: Bool): UInt = {
    Cat(Seq.fill(n)(bit))
  }
}

object CountLeadingZeros {
  def apply(in: UInt): UInt = {
    val dataWidth = in.getWidth
    require(dataWidth >= 1)

    if (dataWidth == 1) {
      return !in(0)
    }

    val nextPow2 = 1 << log2Ceil(dataWidth)

    val paddedIn = Cat(in, 0.U((nextPow2 - dataWidth).W))

    val leaf = scala.math.min(nextPow2, 4)

    val (valid, lzPow2) = lzdPow2(paddedIn, leaf)

    Mux(valid, lzPow2, dataWidth.U)
  }

  private def lzdPow2(in: UInt, leaf: Int): (Bool, UInt) = {
    val dataWidth = in.getWidth

    if (dataWidth == leaf) {
      leafLzd(in, leaf)
    } else {
      val halfWidth = dataWidth >> 1
      val upper = in(dataWidth - 1, halfWidth)
      val lower = in(halfWidth - 1, 0)

      val (uValid, uLz) = lzdPow2(upper, leaf)
      val (lValid, lLz) = lzdPow2(lower, leaf)

      val valid = uValid || lValid
      val clz = Cat(!uValid, Mux(uValid, uLz, lLz))

      (valid, clz)
    }
  }

  private def leafLzd(in: UInt, leaf: Int): (Bool, UInt) = {
    val valid = in.orR
    val clz = leaf match {
      case 2 =>
        decoder(
          minimizer = QMCMinimizer,
          input = in,
          truthTable = TruthTable(
            table = Seq(
              BitPat("b1?") -> BitPat("b0"),
              BitPat("b01") -> BitPat("b1")
            ),
            default = BitPat.dontCare(1)
          )
        )
      case 4 =>
        decoder(
          minimizer = QMCMinimizer,
          input = in,
          truthTable = TruthTable(
            table = Seq(
              BitPat("b1???") -> BitPat("b00"),
              BitPat("b01??") -> BitPat("b01"),
              BitPat("b001?") -> BitPat("b10"),
              BitPat("b0001") -> BitPat("b11")
            ),
            default = BitPat.dontCare(2)
          )
        )
    }
    (valid, clz)
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
