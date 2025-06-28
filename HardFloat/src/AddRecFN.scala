package HardFloat

import chisel3._
import chisel3.util._
import Consts._
import HardUtils._

class AddRawFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val subOp = Input(Bool())
    val a = Input(new RawFloat(expWidth, sigWidth))
    val b = Input(new RawFloat(expWidth, sigWidth))
    val roundingMode = Input(UInt(3.W))
    val invalidExc = Output(Bool())
    val rawOut = Output(new RawFloat(expWidth, sigWidth + 2))
  })

  val alignDistWidth = log2Ceil(sigWidth + 3)

  val effSignB = io.b.sign ^ io.subOp
  val eqSigns = io.a.sign === effSignB
  val notEqSigns_signZero = io.roundingMode === round_min
  val sDiffExps = (io.a.sExp(expWidth, 0) -& io.b.sExp(expWidth, 0)).asSInt
  val modNatAlignDist = sDiffExps.abs(alignDistWidth - 1, 0)
  val isDiffExpsSmall = !sDiffExps(expWidth + 1, alignDistWidth).orR ||
    (sDiffExps(expWidth + 1, alignDistWidth).andR && sDiffExps(alignDistWidth - 1, 0).orR)
  val alignDist = Mux(isDiffExpsSmall, modNatAlignDist, Replicate(alignDistWidth, true.B))
  val closeSubMags = !eqSigns && isDiffExpsSmall && (modNatAlignDist <= 1.U)

  val close_alignedSigA = Mux1H(
    Seq(
      (sDiffExps < 0.S) -> io.a.sig(sigWidth - 1, 0),
      ((sDiffExps >= 0.S) && !sDiffExps(0)) -> Cat(io.a.sig(sigWidth - 1, 0), false.B),
      ((sDiffExps >= 0.S) && sDiffExps(0)) -> Cat(io.a.sig(sigWidth - 1, 0), 0.U(2.W))
    )
  )
  val close_sSigSum = (close_alignedSigA -& Cat(false.B, io.b.sig(sigWidth - 1, 0), false.B)).asSInt
  val close_sigSum = close_sSigSum.abs(sigWidth + 1, 0)
  val close_adjustedSigSum = Cat(close_sigSum, 0.U((sigWidth & 1).W))
  val close_reduced2SigSum = OrReduceBy2(close_adjustedSigSum)
  val close_normDistReduced2 = CountLeadingZeros(close_reduced2SigSum)(log2Ceil((sigWidth + 3) >> 1) - 1, 0)
  val close_nearNormDist = Cat(close_normDistReduced2, false.B)
  val close_sigOut = Cat((close_sigSum << close_nearNormDist)(sigWidth + 1, 0), false.B)
  val close_totalCancellation = close_sigOut(sigWidth + 2, sigWidth + 1) === 0.U
  val close_notTotalCancellation_signOut = io.a.sign ^ (close_sSigSum < 0.S)

  val far_signOut = Mux(sDiffExps < 0.S, effSignB, io.a.sign)
  val far_sigLarger = Mux(sDiffExps < 0.S, io.b.sig(sigWidth - 1, 0), io.a.sig(sigWidth - 1, 0))
  val far_sigSmaller = Mux(sDiffExps < 0.S, io.a.sig(sigWidth - 1, 0), io.b.sig(sigWidth - 1, 0))
  val far_mainAlignedSigSmaller = Cat(far_sigSmaller, 0.U(5.W)) >> alignDist
  val far_reduced4SigSmallerExtra = (
    OrReduceBy4(Cat(far_sigSmaller, 0.U(2.W))) &
      LowMask(alignDist(alignDistWidth - 1, 2), (sigWidth + 5) >> 2, 0)
  ).orR
  val far_alignedSigSmaller = Cat(
    far_mainAlignedSigSmaller(sigWidth + 4, 3),
    far_mainAlignedSigSmaller(2, 0).orR || far_reduced4SigSmallerExtra
  )
  val far_subMags = !eqSigns
  val far_negAlignedSigSmaller = Cat(false.B, far_alignedSigSmaller) ^ Replicate(sigWidth + 4, far_subMags)
  val far_sigSum =
    (Cat(false.B, far_sigLarger, 0.U(3.W), true.B) + Cat(far_negAlignedSigSmaller, far_subMags))(sigWidth + 4, 1)
  val far_sigOut = Mux(far_subMags, far_sigSum(sigWidth + 2, 0), far_sigSum(sigWidth + 3, 1) | far_sigSum(0))

  val notNaN_isInfOut = io.a.isInf || io.b.isInf
  val addZeros = io.a.isZero && io.b.isZero
  val notNaN_specialCase = notNaN_isInfOut || addZeros
  val notNaN_isZeroOut = addZeros || (!notNaN_isInfOut && closeSubMags && close_totalCancellation)
  val notNaN_signOut = (eqSigns && io.a.sign) ||
    (io.a.isInf && io.a.sign) ||
    (io.b.isInf && effSignB) ||
    (notNaN_isZeroOut && !eqSigns && notEqSigns_signZero) ||
    (!notNaN_specialCase && closeSubMags && !close_totalCancellation && close_notTotalCancellation_signOut) ||
    (!notNaN_specialCase && !closeSubMags && far_signOut)
  val common_sExpOut = (Mux(
    closeSubMags || (sDiffExps < 0.S),
    io.b.sExp(expWidth, 0),
    io.a.sExp(expWidth, 0)
  ) -& Mux(
    closeSubMags,
    close_nearNormDist,
    far_subMags
  )).asSInt
  val common_sigOut = Mux(closeSubMags, close_sigOut, far_sigOut)

  io.invalidExc := IsSigNaNRawFloat(io.a) || IsSigNaNRawFloat(io.b) || (io.a.isInf && io.b.isInf && !eqSigns)
  io.rawOut.isNaN := io.a.isNaN || io.b.isNaN
  io.rawOut.isInf := notNaN_isInfOut
  io.rawOut.isZero := notNaN_isZeroOut
  io.rawOut.sign := notNaN_signOut
  io.rawOut.sExp := common_sExpOut
  io.rawOut.sig := common_sigOut
}

class AddRecFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val subOp = Input(Bool())
    val a = Input(UInt((expWidth + sigWidth + 1).W))
    val b = Input(UInt((expWidth + sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val addRawFN = Module(new AddRawFN(expWidth, sigWidth))

  addRawFN.io.subOp := io.subOp
  addRawFN.io.a := RawFloatFromRecFN(expWidth, sigWidth, io.a)
  addRawFN.io.b := RawFloatFromRecFN(expWidth, sigWidth, io.b)
  addRawFN.io.roundingMode := io.roundingMode

  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
  roundRawFNToRecFN.io.invalidExc := addRawFN.io.invalidExc
  roundRawFNToRecFN.io.infiniteExc := false.B
  roundRawFNToRecFN.io.in := addRawFN.io.rawOut
  roundRawFNToRecFN.io.roundingMode := io.roundingMode
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
