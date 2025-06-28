package HardFloat

import chisel3._
import chisel3.util._
import Consts._
import HardUtils._

class MulAddRecFN_interIo(expWidth: Int, sigWidth: Int) extends Bundle {
  val isSigNaNAny = Bool()
  val isNaNAOrB = Bool()
  val isInfA = Bool()
  val isZeroA = Bool()
  val isInfB = Bool()
  val isZeroB = Bool()
  val signProd = Bool()
  val isNaNC = Bool()
  val isInfC = Bool()
  val isZeroC = Bool()
  val sExpSum = SInt((expWidth + 2).W)
  val doSubMags = Bool()
  val cIsDominant = Bool()
  val cDom_cAlignDist = UInt(log2Ceil(sigWidth + 1).W)
  val highAlignedSigC = UInt((sigWidth + 2).W)
  val bit0AlignedSigC = Bool()
}

class MulAddRecFNToRaw_preMul(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val op = Input(UInt(2.W))
    val a = Input(UInt((expWidth + sigWidth + 1).W))
    val b = Input(UInt((expWidth + sigWidth + 1).W))
    val c = Input(UInt((expWidth + sigWidth + 1).W))
    val mulAddA = Output(UInt(sigWidth.W))
    val mulAddB = Output(UInt(sigWidth.W))
    val mulAddC = Output(UInt((2 * sigWidth).W))
    val toPostMul = Output(new MulAddRecFN_interIo(expWidth, sigWidth))
  })

  val sigSumWidth = 3 * sigWidth + 3

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.b)
  val rawC = RawFloatFromRecFN(expWidth, sigWidth, io.c)

  val signProd = rawA.sign ^ rawB.sign ^ io.op(1)
  val sExpAlignedProd =
    ((rawA.sExp(expWidth, 0) +& rawB.sExp(expWidth, 0)) -& ((BigInt(1) << expWidth) - sigWidth - 3).U).asSInt

  val doSubMags = signProd ^ rawC.sign ^ io.op(0)

  val sNatCAlignDist = sExpAlignedProd - rawC.sExp(expWidth, 0).pad(expWidth + 3).asSInt
  val posNatCAlignDist = sNatCAlignDist(expWidth + 1, 0)
  val isMinCAlign = rawA.isZero || rawB.isZero || (sNatCAlignDist < 0.S)
  val cIsDominant = !rawC.isZero && (isMinCAlign || (posNatCAlignDist <= sigWidth.U))
  val cAlignDist = Mux(
    isMinCAlign,
    0.U,
    Mux(
      posNatCAlignDist < (sigSumWidth - 1).U,
      posNatCAlignDist(log2Ceil(sigSumWidth) - 1, 0),
      (sigSumWidth - 1).U
    )
  )
  val mainAlignedSigC = Cat(rawC.sig(sigWidth - 1, 0), 0.U((sigSumWidth - sigWidth + 2).W)) >> cAlignDist
  val reduced4SigCExtra = (
    OrReduceBy4(Cat(rawC.sig(sigWidth - 1 - ((sigSumWidth - 1) & 3), 0), 0.U(((sigSumWidth - sigWidth - 1) & 3).W))) &
      LowMask(cAlignDist(log2Ceil(sigSumWidth) - 1, 2), (sigSumWidth - 1) >> 2, (sigSumWidth - sigWidth - 1) >> 2)
  ).orR
  val alignedSigC = Cat(
    mainAlignedSigC(sigSumWidth + 1, 3),
    mainAlignedSigC(2, 0).orR || reduced4SigCExtra
  ) ^ Replicate(sigSumWidth, doSubMags)

  io.mulAddA := rawA.sig(sigWidth - 1, 0)
  io.mulAddB := rawB.sig(sigWidth - 1, 0)
  io.mulAddC := alignedSigC(2 * sigWidth, 1)

  io.toPostMul.isSigNaNAny := IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || IsSigNaNRawFloat(rawC)
  io.toPostMul.isNaNAOrB := rawA.isNaN || rawB.isNaN
  io.toPostMul.isInfA := rawA.isInf
  io.toPostMul.isZeroA := rawA.isZero
  io.toPostMul.isInfB := rawB.isInf
  io.toPostMul.isZeroB := rawB.isZero
  io.toPostMul.signProd := signProd
  io.toPostMul.isNaNC := rawC.isNaN
  io.toPostMul.isInfC := rawC.isInf
  io.toPostMul.isZeroC := rawC.isZero
  io.toPostMul.sExpSum := Mux(cIsDominant, rawC.sExp, (sExpAlignedProd(expWidth + 1, 0) - sigWidth.U).asSInt)
  io.toPostMul.doSubMags := doSubMags
  io.toPostMul.cIsDominant := cIsDominant
  io.toPostMul.cDom_cAlignDist := cAlignDist(log2Ceil(sigWidth + 1) - 1, 0)
  io.toPostMul.highAlignedSigC := alignedSigC(sigSumWidth - 1, 2 * sigWidth + 1)
  io.toPostMul.bit0AlignedSigC := alignedSigC(0)
}

class MulAddRecFNToRaw_postMul(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val fromPreMul = Input(new MulAddRecFN_interIo(expWidth, sigWidth))
    val mulAddResult = Input(UInt((2 * sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val invalidExc = Output(Bool())
    val rawOut = Output(new RawFloat(expWidth, sigWidth + 2))
  })

  val sigSumWidth = 3 * sigWidth + 3

  val roundingMode_min = io.roundingMode === round_min

  val opSignC = io.fromPreMul.signProd ^ io.fromPreMul.doSubMags
  val sigSum = Cat(
    Mux(
      io.mulAddResult(2 * sigWidth),
      io.fromPreMul.highAlignedSigC + 1.U,
      io.fromPreMul.highAlignedSigC
    ),
    io.mulAddResult(2 * sigWidth - 1, 0),
    io.fromPreMul.bit0AlignedSigC
  )

  val cDom_sign = opSignC
  val cDom_sExp = (io.fromPreMul.sExpSum(expWidth, 0) - io.fromPreMul.doSubMags).pad(expWidth + 2).asSInt
  val cDom_absSigSum = Mux(
    io.fromPreMul.doSubMags,
    ~sigSum(sigSumWidth - 1, sigWidth + 1),
    Cat(false.B, io.fromPreMul.highAlignedSigC(sigWidth + 1, sigWidth), sigSum(sigSumWidth - 3, sigWidth + 2))
  )
  val cDom_absSigSumExtra = Mux(
    io.fromPreMul.doSubMags,
    !sigSum(sigWidth, 1).andR,
    sigSum(sigWidth + 1, 1).orR
  )
  val cDom_mainSig = (cDom_absSigSum << io.fromPreMul.cDom_cAlignDist)(2 * sigWidth + 1, sigWidth - 3)
  val cDom_reduced4SigExtra = (
    OrReduceBy4(Cat(cDom_absSigSum(sigWidth - 4, 0), 0.U((~sigWidth & 3).W))) &
      LowMask(io.fromPreMul.cDom_cAlignDist(log2Ceil(sigWidth + 1) - 1, 2), 0, sigWidth >> 2)
  ).orR
  val cDom_sig = Cat(
    cDom_mainSig(sigWidth + 4, 3),
    cDom_mainSig(2, 0).orR || cDom_reduced4SigExtra || cDom_absSigSumExtra
  )

  val notCDom_signSigSum = sigSum(2 * sigWidth + 3)
  val notCDom_absSigSum = Mux(
    notCDom_signSigSum,
    ~sigSum(2 * sigWidth + 2, 0),
    sigSum(2 * sigWidth + 2, 0) + io.fromPreMul.doSubMags
  )
  val notCDom_reduced2AbsSigSum = OrReduceBy2(notCDom_absSigSum)
  val notCDom_normDistReduced2 = CountLeadingZeros(notCDom_reduced2AbsSigSum)(log2Ceil(sigWidth + 2) - 1, 0)
  val notCDom_nearNormDist = Cat(notCDom_normDistReduced2, false.B)
  val notCDom_sExp = io.fromPreMul.sExpSum - notCDom_nearNormDist.pad(expWidth + 2).asSInt
  val notCDom_mainSig = (notCDom_absSigSum << notCDom_nearNormDist)(2 * sigWidth + 3, sigWidth - 1)
  val notCDom_reduced4SigExtra = (
    OrReduceBy2(Cat(notCDom_reduced2AbsSigSum((sigWidth - 2) >> 1, 0), 0.U(((sigWidth >> 1) & 1).W))) &
      LowMask(notCDom_normDistReduced2(log2Ceil(sigWidth + 2) - 1, 1), 0, (sigWidth + 2) >> 2)
  ).orR
  val notCDom_sig = Cat(
    notCDom_mainSig(sigWidth + 4, 3),
    notCDom_mainSig(2, 0).orR || notCDom_reduced4SigExtra
  )
  val notCDom_completeCancellation = notCDom_sig(sigWidth + 2, sigWidth + 1) === 0.U
  val notCDom_sign = Mux(notCDom_completeCancellation, roundingMode_min, io.fromPreMul.signProd ^ notCDom_signSigSum)

  val notNaN_isInfProd = io.fromPreMul.isInfA || io.fromPreMul.isInfB
  val notNaN_isInfOut = notNaN_isInfProd || io.fromPreMul.isInfC
  val notNaN_addZeros = (io.fromPreMul.isZeroA || io.fromPreMul.isZeroB) && io.fromPreMul.isZeroC

  io.invalidExc := io.fromPreMul.isSigNaNAny ||
    (io.fromPreMul.isInfA && io.fromPreMul.isZeroB) ||
    (io.fromPreMul.isZeroA && io.fromPreMul.isInfB) ||
    (!io.fromPreMul.isNaNAOrB && notNaN_isInfProd && io.fromPreMul.isInfC && io.fromPreMul.doSubMags)
  io.rawOut.isNaN := io.fromPreMul.isNaNAOrB || io.fromPreMul.isNaNC
  io.rawOut.isInf := notNaN_isInfOut
  io.rawOut.isZero := notNaN_addZeros || (!io.fromPreMul.cIsDominant && notCDom_completeCancellation)
  io.rawOut.sign := (notNaN_isInfProd && io.fromPreMul.signProd) ||
    (io.fromPreMul.isInfC && opSignC) ||
    (notNaN_addZeros && !roundingMode_min && io.fromPreMul.signProd && opSignC) ||
    (notNaN_addZeros && roundingMode_min && (io.fromPreMul.signProd || opSignC)) ||
    (!notNaN_isInfOut && !notNaN_addZeros && Mux(io.fromPreMul.cIsDominant, cDom_sign, notCDom_sign))
  io.rawOut.sExp := Mux(io.fromPreMul.cIsDominant, cDom_sExp, notCDom_sExp)
  io.rawOut.sig := Mux(io.fromPreMul.cIsDominant, cDom_sig, notCDom_sig)
}

class MulAddRecFN(expWidth: Int, sigWidth: Int) extends RawModule {
  require(sigWidth >= 4)

  val io = IO(new Bundle {
    val op = Input(UInt(2.W))
    val a = Input(UInt((expWidth + sigWidth + 1).W))
    val b = Input(UInt((expWidth + sigWidth + 1).W))
    val c = Input(UInt((expWidth + sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val mulAddRecFNToRaw_preMul = Module(new MulAddRecFNToRaw_preMul(expWidth, sigWidth))
  val mulAddRecFNToRaw_postMul = Module(new MulAddRecFNToRaw_postMul(expWidth, sigWidth))

  mulAddRecFNToRaw_preMul.io.op := io.op
  mulAddRecFNToRaw_preMul.io.a := io.a
  mulAddRecFNToRaw_preMul.io.b := io.b
  mulAddRecFNToRaw_preMul.io.c := io.c

  val mulAddResult = (mulAddRecFNToRaw_preMul.io.mulAddA * mulAddRecFNToRaw_preMul.io.mulAddB) +&
    mulAddRecFNToRaw_preMul.io.mulAddC

  mulAddRecFNToRaw_postMul.io.fromPreMul := mulAddRecFNToRaw_preMul.io.toPostMul
  mulAddRecFNToRaw_postMul.io.mulAddResult := mulAddResult
  mulAddRecFNToRaw_postMul.io.roundingMode := io.roundingMode

  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
  roundRawFNToRecFN.io.invalidExc := mulAddRecFNToRaw_postMul.io.invalidExc
  roundRawFNToRecFN.io.infiniteExc := false.B
  roundRawFNToRecFN.io.in := mulAddRecFNToRaw_postMul.io.rawOut
  roundRawFNToRecFN.io.roundingMode := io.roundingMode
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
