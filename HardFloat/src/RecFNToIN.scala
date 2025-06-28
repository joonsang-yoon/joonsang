package HardFloat

import chisel3._
import chisel3.util._
import Consts._
import HardUtils._

class RecFNToIN(expWidth: Int, sigWidth: Int, intWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(UInt((expWidth + sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val signedOut = Input(Bool())
    val out = Output(UInt(intWidth.W))
    val intExceptionFlags = Output(UInt(3.W))
  })

  val rawIn = RawFloatFromRecFN(expWidth, sigWidth, io.in)

  val magGeOne = rawIn.sExp(expWidth)
  val magHalfToOne = !rawIn.sExp(expWidth) && rawIn.sExp(expWidth - 1, 0).andR

  val roundingMode_near_even = io.roundingMode === round_near_even
  val roundingMode_minMag = io.roundingMode === round_minMag
  val roundingMode_min = io.roundingMode === round_min
  val roundingMode_max = io.roundingMode === round_max
  val roundingMode_near_maxMag = io.roundingMode === round_near_maxMag
  val roundingMode_odd = io.roundingMode === round_odd

  /*------------------------------------------------------------------------
    | Assuming the input floating-point value is not a NaN, its magnitude is
    | at least 1, and it is not obviously so large as to lead to overflow,
    | convert its significand to fixed-point (i.e., with the binary point in a
    | fixed location).  For a non-NaN input with a magnitude less than 1, this
    | expression contrives to ensure that the integer bits of 'alignedSig'
    | will all be zeros.
   *------------------------------------------------------------------------*/
  val shiftedSig = Cat(magGeOne, rawIn.sig(sigWidth - 2, 0)) << Mux(
    magGeOne,
    rawIn.sExp(scala.math.min(expWidth - 2, log2Ceil(intWidth) - 1), 0),
    0.U
  )
  val alignedSig = Cat(shiftedSig(shiftedSig.getWidth - 1, sigWidth - 2), shiftedSig(sigWidth - 3, 0).orR)
  val unroundedInt = alignedSig(alignedSig.getWidth - 1, 2).pad(intWidth)

  val common_inexact = Mux(magGeOne, alignedSig(1, 0).orR, !rawIn.isZero)
  val roundIncr_near_even = (magGeOne && (alignedSig(2, 1).andR || alignedSig(1, 0).andR)) ||
    (magHalfToOne && alignedSig(1, 0).orR)
  val roundIncr_near_maxMag = (magGeOne && alignedSig(1)) || magHalfToOne
  val roundIncr = (roundingMode_near_even && roundIncr_near_even) ||
    (roundingMode_near_maxMag && roundIncr_near_maxMag) ||
    ((roundingMode_min || roundingMode_odd) && (rawIn.sign && common_inexact)) ||
    (roundingMode_max && (!rawIn.sign && common_inexact))
  val complUnroundedInt = Mux(rawIn.sign, ~unroundedInt, unroundedInt)
  val roundedInt = Mux(
    roundIncr ^ rawIn.sign,
    complUnroundedInt + 1.U,
    complUnroundedInt
  ) | (roundingMode_odd && common_inexact)

  val magGeOne_atOverflowEdge = rawIn.sExp(expWidth - 2, 0) === (intWidth - 1).U
  val roundCarryBut2 = unroundedInt(intWidth - 3, 0).andR && roundIncr
  val common_overflow = Mux(
    magGeOne,
    (rawIn.sExp(expWidth - 2, 0) >= intWidth.U) || Mux(
      io.signedOut,
      Mux(
        rawIn.sign,
        magGeOne_atOverflowEdge && (unroundedInt(intWidth - 2, 0).orR || roundIncr),
        magGeOne_atOverflowEdge || ((rawIn.sExp(expWidth - 2, 0) === (intWidth - 2).U) && roundCarryBut2)
      ),
      rawIn.sign || (magGeOne_atOverflowEdge && unroundedInt(intWidth - 2) && roundCarryBut2)
    ),
    !io.signedOut && rawIn.sign && roundIncr
  )

  val invalidExc = rawIn.isNaN || rawIn.isInf
  val overflow = !invalidExc && common_overflow
  val inexact = !invalidExc && !common_overflow && common_inexact

  val excSign = !rawIn.isNaN && rawIn.sign
  val excOut = Cat(io.signedOut === excSign, Replicate(intWidth - 1, !excSign))

  io.out := Mux(invalidExc || common_overflow, excOut, roundedInt)
  io.intExceptionFlags := Cat(invalidExc, overflow, inexact)
}
