package HardFloat

import chisel3._
import chisel3.util._
import HardUtils._

object Consts {
  def round_near_even = "b000".U(3.W)
  def round_minMag = "b001".U(3.W)
  def round_min = "b010".U(3.W)
  def round_max = "b011".U(3.W)
  def round_near_maxMag = "b100".U(3.W)
  def round_odd = "b110".U(3.W)

  def tininess_beforeRounding = 0.U
  def tininess_afterRounding = 1.U

  def flRoundOpt_sigMSBitAlwaysZero = 1
  def flRoundOpt_subnormsAlwaysExact = 2
  def flRoundOpt_neverUnderflows = 4
  def flRoundOpt_neverOverflows = 8
}

class RawFloat(val expWidth: Int, val sigWidth: Int) extends Bundle {
  val isNaN = Bool() // Overrides all other fields
  val isInf = Bool() // Overrides 'isZero', 'sExp', and 'sig'
  val isZero = Bool() // Overrides 'sExp' and 'sig'
  val sign = Bool()
  val sExp = SInt((expWidth + 2).W)
  val sig = UInt((sigWidth + 1).W) // For finite non-zero numbers, the two MSBs (integer part) cannot both be "00"
}

object IsSigNaNRawFloat {
  def apply(in: RawFloat): Bool = {
    in.isNaN && !in.sig(in.sigWidth - 2)
  }
}

object RawFloatFromIN {
  def apply(signedIn: Bool, in: UInt): RawFloat = {
    val expWidth = log2Ceil(in.getWidth) + 1
    val extIntWidth = 1 << (expWidth - 1)

    val sign = signedIn && in(in.getWidth - 1)
    val absIn = Mux(sign, -in, in)
    val extAbsIn = absIn.pad(extIntWidth)
    val adjustedNormDist = CountLeadingZeros(extAbsIn)(expWidth - 2, 0)
    val sig = (extAbsIn << adjustedNormDist)(extIntWidth - 1, extIntWidth - in.getWidth)

    val out = Wire(new RawFloat(expWidth, in.getWidth))
    out.isNaN := false.B
    out.isInf := false.B
    out.isZero := !sig(in.getWidth - 1)
    out.sign := sign
    out.sExp := Cat("b010".U(3.W), ~adjustedNormDist).asSInt
    out.sig := Cat(false.B, sig)
    out
  }
}

object RawFloatFromFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): RawFloat = {
    require(sigWidth >= 3)
    require(sigWidth <= (1 << (expWidth - 2)) + 3)

    val sign = in(expWidth + sigWidth - 1)
    val expIn = in(expWidth + sigWidth - 2, sigWidth - 1)
    val fractIn = in(sigWidth - 2, 0)

    val isZeroExpIn = expIn === 0.U
    val isZeroFractIn = fractIn === 0.U

    val normDist = CountLeadingZeros(fractIn)(log2Ceil(sigWidth - 1) - 1, 0)
    val subnormFract = Cat((fractIn << normDist)(sigWidth - 3, 0), false.B)
    val adjustedExp = Mux(
      isZeroExpIn,
      ~(normDist.pad(expWidth + 1)),
      expIn
    ) + ((BigInt(1) << (expWidth - 1)).U | Mux(
      isZeroExpIn,
      2.U,
      1.U
    ))

    val isZero = isZeroExpIn && isZeroFractIn
    val isSpecial = adjustedExp(expWidth, expWidth - 1) === 3.U

    val out = Wire(new RawFloat(expWidth, sigWidth))
    out.isNaN := isSpecial && !isZeroFractIn
    out.isInf := isSpecial && isZeroFractIn
    out.isZero := isZero
    out.sign := sign
    out.sExp := Cat(false.B, adjustedExp).asSInt
    out.sig := Cat(false.B, !isZero, Mux(isZeroExpIn, subnormFract, fractIn))
    out
  }
}

object RecFNFromFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): UInt = {
    require(expWidth >= 3)

    val rawIn = RawFloatFromFN(expWidth, sigWidth, in)
    Cat(
      rawIn.sign,
      Mux(
        rawIn.isZero,
        0.U(3.W),
        rawIn.sExp(expWidth, expWidth - 2)
      ) | Mux(
        rawIn.isNaN,
        1.U,
        0.U
      ),
      rawIn.sExp(expWidth - 3, 0),
      rawIn.sig(sigWidth - 2, 0)
    )
  }
}

object RawFloatFromRecFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): RawFloat = {
    val exp = in(expWidth + sigWidth - 1, sigWidth - 1)
    val isZero = exp(expWidth, expWidth - 2) === 0.U
    val isSpecial = exp(expWidth, expWidth - 1) === 3.U

    val out = Wire(new RawFloat(expWidth, sigWidth))
    out.isNaN := isSpecial && exp(expWidth - 2)
    out.isInf := isSpecial && !exp(expWidth - 2)
    out.isZero := isZero
    out.sign := in(expWidth + sigWidth)
    out.sExp := Cat(false.B, exp).asSInt
    out.sig := Cat(false.B, !isZero, in(sigWidth - 2, 0))
    out
  }
}

object FNFromRecFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): UInt = {
    val minNormExp = (BigInt(1) << (expWidth - 1)) + 2

    val rawIn = RawFloatFromRecFN(expWidth, sigWidth, in)

    val isSubnormal = rawIn.sExp(expWidth, 0) < minNormExp.U
    val denormShiftDist = 1.U - rawIn.sExp(log2Ceil(sigWidth - 1) - 1, 0)
    val denormFract = rawIn.sig(sigWidth - 1, 1) >> denormShiftDist

    val expOut = Mux(
      isSubnormal,
      0.U,
      rawIn.sExp(expWidth - 1, 0) - ((BigInt(1) << (expWidth - 1)) + 1).U
    ) | Replicate(expWidth, rawIn.isNaN || rawIn.isInf)
    val fractOut = Mux(
      isSubnormal,
      denormFract,
      Mux(
        rawIn.isInf,
        0.U,
        rawIn.sig(sigWidth - 2, 0)
      )
    )
    Cat(rawIn.sign, expOut, fractOut)
  }
}
