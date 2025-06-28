package HardFloat

import chisel3._
import chisel3.util._
import Consts._
import HardUtils._

class RoundAnyRawFNToRecFN(inExpWidth: Int, inSigWidth: Int, outExpWidth: Int, outSigWidth: Int, options: Int)
    extends RawModule {
  val io = IO(new Bundle {
    val invalidExc = Input(Bool()) // Overrides 'infiniteExc' and 'in'
    val infiniteExc = Input(Bool()) // Overrides 'in' except for 'in.sign'
    val in = Input(new RawFloat(inExpWidth, inSigWidth))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((outExpWidth + outSigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val sigMSBitAlwaysZero = (options & flRoundOpt_sigMSBitAlwaysZero) != 0
  val effectiveInSigWidth = if (sigMSBitAlwaysZero) {
    inSigWidth
  } else {
    inSigWidth + 1
  }
  val neverUnderflows = ((options & (flRoundOpt_neverUnderflows | flRoundOpt_subnormsAlwaysExact)) != 0) ||
    (inExpWidth < outExpWidth)
  val neverOverflows = ((options & flRoundOpt_neverOverflows) != 0) || (inExpWidth < outExpWidth)
  val outNaNExp = BigInt(7) << (outExpWidth - 2)
  val outInfExp = BigInt(6) << (outExpWidth - 2)
  val outMaxFiniteExp = outInfExp - 1
  val outMinNormExp = (BigInt(1) << (outExpWidth - 1)) + 2
  val outMinNonzeroExp = outMinNormExp - outSigWidth + 1

  val roundingMode_near_even = io.roundingMode === round_near_even
  val roundingMode_minMag = io.roundingMode === round_minMag
  val roundingMode_min = io.roundingMode === round_min
  val roundingMode_max = io.roundingMode === round_max
  val roundingMode_near_maxMag = io.roundingMode === round_near_maxMag
  val roundingMode_odd = io.roundingMode === round_odd

  val roundMagUp = (roundingMode_min && io.in.sign) || (roundingMode_max && !io.in.sign)

  val sAdjustedExp = if (inExpWidth < outExpWidth) {
    (
      io.in.sExp(inExpWidth, 0) +& ((BigInt(1) << outExpWidth) - (BigInt(1) << inExpWidth)).U
    ).pad(outExpWidth + 2).asSInt
  } else if (inExpWidth == outExpWidth) {
    io.in.sExp
  } else {
    (io.in.sExp(inExpWidth, 0) - ((BigInt(1) << inExpWidth) - (BigInt(1) << outExpWidth)).U).asSInt
  }
  val adjustedSig = if (inSigWidth <= outSigWidth + 2) {
    Cat(io.in.sig, 0.U((outSigWidth - inSigWidth + 2).W))
  } else {
    Cat(io.in.sig(inSigWidth, inSigWidth - outSigWidth - 1), io.in.sig(inSigWidth - outSigWidth - 2, 0).orR)
  }
  val doShiftSigDown1 = if (sigMSBitAlwaysZero) {
    false.B
  } else {
    adjustedSig(outSigWidth + 2)
  }

  val common_expOut = Wire(UInt((outExpWidth + 1).W))
  val common_fractOut = Wire(UInt((outSigWidth - 1).W))
  val common_overflow = Wire(Bool())
  val common_totalUnderflow = Wire(Bool())
  val common_underflow = Wire(Bool())
  val common_inexact = Wire(Bool())

  if (neverOverflows && neverUnderflows && (effectiveInSigWidth <= outSigWidth)) {
    common_expOut := sAdjustedExp(outExpWidth, 0) + doShiftSigDown1
    common_fractOut := Mux(doShiftSigDown1, adjustedSig(outSigWidth + 1, 3), adjustedSig(outSigWidth, 2))
    common_overflow := false.B
    common_totalUnderflow := false.B
    common_underflow := false.B
    common_inexact := false.B
  } else {
    val roundMask = if (neverUnderflows) {
      Cat(0.U(outSigWidth.W), doShiftSigDown1, 3.U(2.W))
    } else {
      Cat(
        LowMask(sAdjustedExp(outExpWidth, 0), outMinNormExp - outSigWidth - 1, outMinNormExp) | doShiftSigDown1,
        3.U(2.W)
      )
    }

    val shiftedRoundMask = Cat(false.B, roundMask(outSigWidth + 2, 1))
    val roundPosMask = ~shiftedRoundMask & roundMask
    val roundPosBit = (adjustedSig & roundPosMask).orR
    val anyRoundExtra = (adjustedSig & shiftedRoundMask).orR
    val anyRound = roundPosBit || anyRoundExtra

    val roundIncr = ((roundingMode_near_even || roundingMode_near_maxMag) && roundPosBit) || (roundMagUp && anyRound)
    val roundedSig = Mux(
      roundIncr,
      ((adjustedSig | roundMask)(outSigWidth + 2, 2) +& 1.U) & ~Mux(
        roundingMode_near_even && roundPosBit && !anyRoundExtra,
        roundMask(outSigWidth + 2, 1),
        0.U((outSigWidth + 2).W)
      ),
      (adjustedSig & ~roundMask)(outSigWidth + 2, 2) | Mux(
        roundingMode_odd && anyRound,
        roundPosMask(outSigWidth + 2, 1),
        0.U((outSigWidth + 2).W)
      )
    )
    val sRoundedExp = if (inExpWidth == outExpWidth + 1) {
      sAdjustedExp +& roundedSig(outSigWidth + 1, outSigWidth).pad(sAdjustedExp.getWidth).asSInt
    } else {
      sAdjustedExp + roundedSig(outSigWidth + 1, outSigWidth).pad(sAdjustedExp.getWidth).asSInt
    }

    common_expOut := sRoundedExp(outExpWidth, 0)
    common_fractOut := Mux(doShiftSigDown1, roundedSig(outSigWidth - 1, 1), roundedSig(outSigWidth - 2, 0))
    common_overflow := (if (neverOverflows) {
                          false.B
                        } else {
                          sRoundedExp(sRoundedExp.getWidth - 1, outExpWidth - 1).asSInt >= 3.S
                        })
    common_totalUnderflow := (if (neverUnderflows) {
                                false.B
                              } else {
                                sRoundedExp < outMinNonzeroExp.S
                              })

    val roundCarry = Mux(doShiftSigDown1, roundedSig(outSigWidth + 1), roundedSig(outSigWidth))
    val unboundedRange_roundPosBit = Mux(doShiftSigDown1, adjustedSig(2), adjustedSig(1))
    val unboundedRange_anyRound = (doShiftSigDown1 && adjustedSig(2)) || adjustedSig(1, 0).orR
    val unboundedRange_roundIncr =
      ((roundingMode_near_even || roundingMode_near_maxMag) && unboundedRange_roundPosBit) ||
        (roundMagUp && unboundedRange_anyRound)
    common_underflow := (if (neverUnderflows) {
                           false.B
                         } else {
                           common_totalUnderflow ||
                           (anyRound && (sAdjustedExp(sAdjustedExp.getWidth - 1, outExpWidth) === 0.U) && Mux(
                             doShiftSigDown1,
                             roundMask(3),
                             roundMask(2)
                           ) && !((io.detectTininess === tininess_afterRounding) && !Mux(
                             doShiftSigDown1,
                             roundMask(4),
                             roundMask(3)
                           ) && roundCarry && roundPosBit && unboundedRange_roundIncr))
                         })

    common_inexact := common_totalUnderflow || anyRound
  }

  val isNaNOut = io.invalidExc || io.in.isNaN
  val notNaN_isSpecialInfOut = io.infiniteExc || io.in.isInf
  val commonCase = !isNaNOut && !notNaN_isSpecialInfOut && !io.in.isZero
  val overflow = commonCase && common_overflow
  val underflow = commonCase && common_underflow
  val inexact = overflow || (commonCase && common_inexact)

  val overflow_roundMagUp = roundingMode_near_even || roundingMode_near_maxMag || roundMagUp
  val pegMinNonzeroMagOut = commonCase && common_totalUnderflow && (roundMagUp || roundingMode_odd)
  val pegMaxFiniteMagOut = overflow && !overflow_roundMagUp
  val notNaN_isInfOut = notNaN_isSpecialInfOut || (overflow && overflow_roundMagUp)

  val signOut = Mux(isNaNOut, false.B, io.in.sign)
  val expOut = (
    common_expOut &
      ~Mux(io.in.isZero || common_totalUnderflow, (BigInt(7) << (outExpWidth - 2)).U((outExpWidth + 1).W), 0.U) &
      ~Mux(pegMinNonzeroMagOut, ~outMinNonzeroExp.U((outExpWidth + 1).W), 0.U) &
      ~Mux(pegMaxFiniteMagOut, ~outMaxFiniteExp.U((outExpWidth + 1).W), 0.U) &
      ~Mux(notNaN_isInfOut, (BigInt(1) << (outExpWidth - 2)).U((outExpWidth + 1).W), 0.U)
  ) | Mux(pegMinNonzeroMagOut, outMinNonzeroExp.U((outExpWidth + 1).W), 0.U) |
    Mux(pegMaxFiniteMagOut, outMaxFiniteExp.U((outExpWidth + 1).W), 0.U) |
    Mux(notNaN_isInfOut, outInfExp.U((outExpWidth + 1).W), 0.U) |
    Mux(isNaNOut, outNaNExp.U((outExpWidth + 1).W), 0.U)
  val fractOut = Mux(
    isNaNOut || io.in.isZero || common_totalUnderflow,
    Cat(isNaNOut, 0.U((outSigWidth - 2).W)),
    common_fractOut
  ) | Replicate(outSigWidth - 1, pegMaxFiniteMagOut)

  io.out := Cat(signOut, expOut, fractOut)
  io.exceptionFlags := Cat(io.invalidExc, io.infiniteExc, overflow, underflow, inexact)
}

class RoundRawFNToRecFN(expWidth: Int, sigWidth: Int, options: Int) extends RawModule {
  val io = IO(new Bundle {
    val invalidExc = Input(Bool()) // Overrides 'infiniteExc' and 'in'
    val infiniteExc = Input(Bool()) // Overrides 'in' except for 'in.sign'
    val in = Input(new RawFloat(expWidth, sigWidth + 2))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val roundAnyRawFNToRecFN = Module(new RoundAnyRawFNToRecFN(expWidth, sigWidth + 2, expWidth, sigWidth, options))
  roundAnyRawFNToRecFN.io.invalidExc := io.invalidExc
  roundAnyRawFNToRecFN.io.infiniteExc := io.infiniteExc
  roundAnyRawFNToRecFN.io.in := io.in
  roundAnyRawFNToRecFN.io.roundingMode := io.roundingMode
  roundAnyRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out := roundAnyRawFNToRecFN.io.out
  io.exceptionFlags := roundAnyRawFNToRecFN.io.exceptionFlags
}
