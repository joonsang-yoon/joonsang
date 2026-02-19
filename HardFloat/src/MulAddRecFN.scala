package HardFloat

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import Consts._
import HardUtils._

class PreMulAddStageInput(expWidth: Int, sigWidth: Int) extends Bundle {
  val op = UInt(2.W)
  val a = UInt((expWidth + sigWidth + 1).W)
  val b = UInt((expWidth + sigWidth + 1).W)
  val c = UInt((expWidth + sigWidth + 1).W)
  val roundingMode = UInt(3.W)
}

class MulAddMetadata(expWidth: Int, sigWidth: Int) extends Bundle {
  val invalidExc = Bool()
  val isNaN = Bool()
  val notNaN_isInfProd = Bool()
  val notNaN_addZeros = Bool()
  val signProd = Bool()
  val isInfC = Bool()
  val sExpSum = SInt((expWidth + 2).W)
  val doSubMags = Bool()
  val cIsDominant = Bool()
  val cDom_cAlignDist = UInt(log2Ceil(sigWidth + 1).W)
  val highAlignedSigC = UInt((sigWidth + 2).W)
  val bit0AlignedSigC = Bool()
  val roundingMode = UInt(3.W)
}

class MulAddStage1Input(expWidth: Int, sigWidth: Int) extends Bundle {
  val in1Sig = UInt(sigWidth.W)
  val in2Sig = UInt(sigWidth.W)
  val in3Sig = UInt((2 * sigWidth).W)
  val mulAddMetadata = new MulAddMetadata(expWidth, sigWidth)
}

class MulAddStage1ToStage2(expWidth: Int, sigWidth: Int) extends Bundle {
  val partialProductColumns = Vec(2 * sigWidth + 1, Vec(sigWidth, Bool()))
  val in3Sig = UInt((2 * sigWidth).W)
  val mulAddMetadata = new MulAddMetadata(expWidth, sigWidth)
}

class MulAddStage2ToStage3(expWidth: Int, sigWidth: Int) extends Bundle {
  val mulAddResult = UInt((2 * sigWidth + 1).W)
  val mulAddMetadata = new MulAddMetadata(expWidth, sigWidth)
}

class MulAddStage3Output(expWidth: Int, sigWidth: Int) extends Bundle {
  val outSig = UInt((sigWidth + 3).W)
  val roundMetadata = UInt((expWidth + 10).W)
}

class PostMulAddStageOutput(expWidth: Int, sigWidth: Int) extends Bundle {
  val out = UInt((expWidth + sigWidth + 1).W)
  val exceptionFlags = UInt(5.W)
}

class PreMulAddStage(expWidth: Int, sigWidth: Int) extends RawModule {
  override def desiredName: String = s"PreMulAddStage_ew${expWidth}_sw${sigWidth}"

  val io = IO(new Bundle {
    val in = Input(new PreMulAddStageInput(expWidth, sigWidth))
    val out = Output(new MulAddStage1Input(expWidth, sigWidth))
  })

  val sigSumWidth = 3 * sigWidth + 3

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.in.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.in.b)
  val rawC = RawFloatFromRecFN(expWidth, sigWidth, io.in.c)

  val signProd = rawA.sign ^ rawB.sign ^ io.in.op(1)
  val sExpAlignedProd =
    ((rawA.sExp(expWidth, 0) +& rawB.sExp(expWidth, 0)) -& ((BigInt(1) << expWidth) - sigWidth - 3).U).asSInt

  val doSubMags = signProd ^ rawC.sign ^ io.in.op(0)

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

  val notNaN_isInfProd = rawA.isInf || rawB.isInf
  val notNaN_addZeros = (rawA.isZero || rawB.isZero) && rawC.isZero

  io.out.in1Sig := rawA.sig(sigWidth - 1, 0)
  io.out.in2Sig := rawB.sig(sigWidth - 1, 0)
  io.out.in3Sig := alignedSigC(2 * sigWidth, 1)
  io.out.mulAddMetadata.invalidExc := IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || IsSigNaNRawFloat(rawC) ||
    (rawA.isInf && rawB.isZero) ||
    (rawA.isZero && rawB.isInf) ||
    (!(rawA.isNaN || rawB.isNaN) && notNaN_isInfProd && rawC.isInf && doSubMags)
  io.out.mulAddMetadata.isNaN := rawA.isNaN || rawB.isNaN || rawC.isNaN
  io.out.mulAddMetadata.notNaN_isInfProd := notNaN_isInfProd
  io.out.mulAddMetadata.notNaN_addZeros := notNaN_addZeros
  io.out.mulAddMetadata.signProd := signProd
  io.out.mulAddMetadata.isInfC := rawC.isInf
  io.out.mulAddMetadata.sExpSum := Mux(cIsDominant, rawC.sExp, (sExpAlignedProd(expWidth + 1, 0) - sigWidth.U).asSInt)
  io.out.mulAddMetadata.doSubMags := doSubMags
  io.out.mulAddMetadata.cIsDominant := cIsDominant
  io.out.mulAddMetadata.cDom_cAlignDist := cAlignDist(log2Ceil(sigWidth + 1) - 1, 0)
  io.out.mulAddMetadata.highAlignedSigC := alignedSigC(sigSumWidth - 1, 2 * sigWidth + 1)
  io.out.mulAddMetadata.bit0AlignedSigC := alignedSigC(0)
  io.out.mulAddMetadata.roundingMode := io.in.roundingMode
}

class MulAddStage1(expWidth: Int, sigWidth: Int, initHeight: Int) extends RawModule {
  override def desiredName: String = s"MulAddStage1_ew${expWidth}_sw${sigWidth}_initHeight${initHeight}"

  val io = IO(new Bundle {
    val in = Input(new MulAddStage1Input(expWidth, sigWidth))
    val out = Output(new MulAddStage1ToStage2(expWidth, sigWidth))
  })

  val numBoothGroups = (sigWidth >> 1) + 1
  val boothExtendedMultiplier = Cat(0.U((2 * numBoothGroups - sigWidth).W), io.in.in2Sig, false.B)

  val boothEncodingPos0 = BitPat("b000") // Use +0 * Multiplicand (isNeg=0, Mag=0)
  val boothEncodingPos1 = BitPat("b001") // Use +1 * Multiplicand (isNeg=0, Mag=1)
  val boothEncodingPos2 = BitPat("b010") // Use +2 * Multiplicand (isNeg=0, Mag=2)
  val boothEncodingNeg2 = BitPat("b110") // Use -2 * Multiplicand (isNeg=1, Mag=2)
  val boothEncodingNeg1 = BitPat("b101") // Use -1 * Multiplicand (isNeg=1, Mag=1)
  val boothEncodingNeg0 = BitPat("b100") // Use -0 * Multiplicand (isNeg=1, Mag=0)

  var partialProductColumns = Array.fill(2 * sigWidth + 1)(Seq.empty[Bool])

  for (i <- 0 until numBoothGroups) {
    val boothEncoding = decoder(
      minimizer = QMCMinimizer,
      input = boothExtendedMultiplier(2 * i + 2, 2 * i),
      truthTable = TruthTable(
        table = Seq(
          BitPat("b000") -> boothEncodingPos0,
          BitPat("b001") -> boothEncodingPos1,
          BitPat("b010") -> boothEncodingPos1,
          BitPat("b011") -> boothEncodingPos2,
          BitPat("b100") -> boothEncodingNeg2,
          BitPat("b101") -> boothEncodingNeg1,
          BitPat("b110") -> boothEncodingNeg1,
          BitPat("b111") -> boothEncodingNeg0
        ),
        default = BitPat.dontCare(3)
      )
    )

    val isNeg = boothEncoding(2)
    val isMag2 = boothEncoding(1)
    val isMag1 = boothEncoding(0)

    val partialProduct = Wire(Vec(sigWidth + 1, Bool()))
    for (j <- partialProduct.indices) {
      partialProduct(j) := (j match {
        case 0 =>
          ((io.in.in1Sig(0) && isMag1) || (false.B && isMag2)) ^ isNeg
        case x if (x == sigWidth) =>
          ((false.B && isMag1) || (io.in.in1Sig(sigWidth - 1) && isMag2)) ^ isNeg
        case _ =>
          ((io.in.in1Sig(j) && isMag1) || (io.in.in1Sig(j - 1) && isMag2)) ^ isNeg
      })
    }

    val partialProductSign = isNeg
    val invertedPartialProductSign = !partialProductSign

    val bitWeight = 2 * i
    val extendedPartialProduct = if (i == 0) {
      Cat(invertedPartialProductSign, Replicate(2, partialProductSign), partialProduct.asUInt)
    } else {
      Cat(true.B, invertedPartialProductSign, partialProduct.asUInt)
    }

    for (j <- partialProductColumns.indices) {
      if ((j >= bitWeight) && ((j - bitWeight) < extendedPartialProduct.getWidth)) {
        partialProductColumns(j) = partialProductColumns(j) :+ extendedPartialProduct(j - bitWeight)
      }
    }
    partialProductColumns(bitWeight) = partialProductColumns(bitWeight) :+ isNeg
  }

  val counterUsage = CounterUsage()
  val daddaHeightSchedule = DaddaHeightScheduleCarryChain(partialProductColumns.map(_.size).max, initHeight)
  var step = 0
  for (_ <- daddaHeightSchedule) {
    partialProductColumns = DaddaReducerCarryChain(partialProductColumns, daddaHeightSchedule(step), counterUsage)
    step += 1
  }
  println(
    s"${desiredName}: Dadda Reduction (Initial) - steps = ${step}, " +
      s"5:3 counter = ${counterUsage.num5to3}, " +
      s"4:3 counter = ${counterUsage.num4to3}, " +
      s"3:2 counter = ${counterUsage.num3to2}, " +
      s"2:2 counter = ${counterUsage.num2to2}"
  )

  io.out.partialProductColumns := VecInit(
    partialProductColumns.toSeq.map(col => VecInit(col.padTo(sigWidth, false.B)))
  )
  io.out.in3Sig := io.in.in3Sig
  io.out.mulAddMetadata := io.in.mulAddMetadata

  def getColumnSizes: Seq[Int] = partialProductColumns.toSeq.map(_.size)
}

class MulAddStage2(expWidth: Int, sigWidth: Int, columnSizes: Seq[Int]) extends RawModule {
  override def desiredName: String = s"MulAddStage2_ew${expWidth}_sw${sigWidth}_maxCol${columnSizes.max}"

  val io = IO(new Bundle {
    val in = Input(new MulAddStage1ToStage2(expWidth, sigWidth))
    val out = Output(new MulAddStage2ToStage3(expWidth, sigWidth))
  })

  var partialProductColumns = Array.fill(2 * sigWidth + 1)(Seq.empty[Bool])
  for (i <- partialProductColumns.indices) {
    if (i < io.in.partialProductColumns.size) {
      partialProductColumns(i) = partialProductColumns(i) ++ io.in.partialProductColumns(i).take(columnSizes(i)).toSeq
    }
    if (i < io.in.in3Sig.getWidth) {
      partialProductColumns(i) = partialProductColumns(i) :+ io.in.in3Sig(i)
    }
  }

  val counterUsage = CounterUsage()
  val daddaHeightSchedule = DaddaHeightScheduleCarryChain(partialProductColumns.map(_.size).max)
  var step = 0
  for (_ <- daddaHeightSchedule) {
    partialProductColumns = DaddaReducerCarryChain(partialProductColumns, daddaHeightSchedule(step), counterUsage)
    step += 1
  }
  println(
    s"${desiredName}: Dadda Reduction (Final) - steps = ${step}, " +
      s"5:3 counter = ${counterUsage.num5to3}, " +
      s"4:3 counter = ${counterUsage.num4to3}, " +
      s"3:2 counter = ${counterUsage.num3to2}, " +
      s"2:2 counter = ${counterUsage.num2to2}"
  )

  io.out.mulAddResult := FinalAdder(partialProductColumns)
  io.out.mulAddMetadata := io.in.mulAddMetadata
}

class MulAddStage3(expWidth: Int, sigWidth: Int) extends RawModule {
  override def desiredName: String = s"MulAddStage3_ew${expWidth}_sw${sigWidth}"

  val io = IO(new Bundle {
    val in = Input(new MulAddStage2ToStage3(expWidth, sigWidth))
    val out = Output(new MulAddStage3Output(expWidth, sigWidth))
  })

  val sigSumWidth = 3 * sigWidth + 3

  val roundingMode_min = io.in.mulAddMetadata.roundingMode === round_min

  val opSignC = io.in.mulAddMetadata.signProd ^ io.in.mulAddMetadata.doSubMags
  val sigSum = Cat(
    Mux(
      io.in.mulAddResult(2 * sigWidth),
      io.in.mulAddMetadata.highAlignedSigC + 1.U,
      io.in.mulAddMetadata.highAlignedSigC
    ),
    io.in.mulAddResult(2 * sigWidth - 1, 0),
    io.in.mulAddMetadata.bit0AlignedSigC
  )

  val cDom_sign = opSignC
  val cDom_sExp = (io.in.mulAddMetadata.sExpSum(expWidth, 0) - io.in.mulAddMetadata.doSubMags).pad(expWidth + 2).asSInt
  val cDom_absSigSum = Mux(
    io.in.mulAddMetadata.doSubMags,
    ~sigSum(sigSumWidth - 1, sigWidth + 1),
    Cat(false.B, io.in.mulAddMetadata.highAlignedSigC(sigWidth + 1, sigWidth), sigSum(sigSumWidth - 3, sigWidth + 2))
  )
  val cDom_absSigSumExtra = Mux(
    io.in.mulAddMetadata.doSubMags,
    !sigSum(sigWidth, 1).andR,
    sigSum(sigWidth + 1, 1).orR
  )
  val cDom_mainSig = (cDom_absSigSum << io.in.mulAddMetadata.cDom_cAlignDist)(2 * sigWidth + 1, sigWidth - 3)
  val cDom_reduced4SigExtra = (
    OrReduceBy4(Cat(cDom_absSigSum(sigWidth - 4, 0), 0.U((~sigWidth & 3).W))) &
      LowMask(io.in.mulAddMetadata.cDom_cAlignDist(log2Ceil(sigWidth + 1) - 1, 2), 0, sigWidth >> 2)
  ).orR
  val cDom_sig = Cat(
    cDom_mainSig(sigWidth + 4, 3),
    cDom_mainSig(2, 0).orR || cDom_reduced4SigExtra || cDom_absSigSumExtra
  )

  val notCDom_signSigSum = sigSum(2 * sigWidth + 3)
  val notCDom_absSigSum = Mux(
    notCDom_signSigSum,
    ~sigSum(2 * sigWidth + 2, 0),
    sigSum(2 * sigWidth + 2, 0) + io.in.mulAddMetadata.doSubMags
  )
  val notCDom_reduced2AbsSigSum = OrReduceBy2(notCDom_absSigSum)
  val notCDom_normDistReduced2 = CountLeadingZeros(notCDom_reduced2AbsSigSum)(log2Ceil(sigWidth + 2) - 1, 0)
  val notCDom_nearNormDist = Cat(notCDom_normDistReduced2, false.B)
  val notCDom_sExp = io.in.mulAddMetadata.sExpSum - notCDom_nearNormDist.pad(expWidth + 2).asSInt
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
  val notCDom_sign =
    Mux(notCDom_completeCancellation, roundingMode_min, io.in.mulAddMetadata.signProd ^ notCDom_signSigSum)

  val notNaN_isInfOut = io.in.mulAddMetadata.notNaN_isInfProd || io.in.mulAddMetadata.isInfC
  val notNaN_isZeroOut =
    io.in.mulAddMetadata.notNaN_addZeros || (!io.in.mulAddMetadata.cIsDominant && notCDom_completeCancellation)
  val notNaN_signOut = (io.in.mulAddMetadata.notNaN_isInfProd && io.in.mulAddMetadata.signProd) ||
    (io.in.mulAddMetadata.isInfC && opSignC) ||
    (io.in.mulAddMetadata.notNaN_addZeros && !roundingMode_min && io.in.mulAddMetadata.signProd && opSignC) ||
    (io.in.mulAddMetadata.notNaN_addZeros && roundingMode_min && (io.in.mulAddMetadata.signProd || opSignC)) ||
    (!notNaN_isInfOut && !io.in.mulAddMetadata.notNaN_addZeros && Mux(
      io.in.mulAddMetadata.cIsDominant,
      cDom_sign,
      notCDom_sign
    ))
  val common_sExpOut = Mux(
    io.in.mulAddMetadata.cIsDominant,
    cDom_sExp,
    notCDom_sExp
  )

  io.out.outSig := Mux(
    io.in.mulAddMetadata.cIsDominant,
    cDom_sig,
    notCDom_sig
  )
  io.out.roundMetadata := Cat(
    io.in.mulAddMetadata.invalidExc,
    io.in.mulAddMetadata.isNaN,
    notNaN_isInfOut,
    notNaN_isZeroOut,
    notNaN_signOut,
    common_sExpOut,
    io.in.mulAddMetadata.roundingMode
  )
}

class MulAddRecFN(expWidth: Int, sigWidth: Int, initHeight: Int) extends Module {
  override def desiredName: String = s"MulAddRecFN_ew${expWidth}_sw${sigWidth}_initHeight${initHeight}"

  require(sigWidth >= 4)

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreMulAddStageInput(expWidth, sigWidth)))
    val detectTininess = Input(Bool())
    val resp = Decoupled(new PostMulAddStageOutput(expWidth, sigWidth))
  })

  val preStage = Module(new PreMulAddStage(expWidth, sigWidth))
  val stage1 = Module(new MulAddStage1(expWidth, sigWidth, initHeight))
  val columnSizes = stage1.getColumnSizes
  val buffer1 = Module(new SkidBuffer(new MulAddStage1ToStage2(expWidth, sigWidth), columnSizes.max > 2))
  val stage2 = Module(new MulAddStage2(expWidth, sigWidth, columnSizes))
  val stage3 = Module(new MulAddStage3(expWidth, sigWidth))
  val buffer2 = Module(new SkidBuffer(new MulAddStage3Output(expWidth, sigWidth), columnSizes.max > 2))
  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))

  preStage.io.in := io.req.bits
  stage1.io.in := preStage.io.out
  buffer1.io.enq.bits := stage1.io.out
  buffer1.io.enq.valid := io.req.valid
  io.req.ready := buffer1.io.enq.ready

  stage2.io.in := buffer1.io.deq.bits
  stage3.io.in := stage2.io.out
  buffer2.io.enq.bits := stage3.io.out
  buffer2.io.enq.valid := buffer1.io.deq.valid
  buffer1.io.deq.ready := buffer2.io.enq.ready

  roundRawFNToRecFN.io.invalidExc := buffer2.io.deq.bits.roundMetadata(expWidth + 9)
  roundRawFNToRecFN.io.infiniteExc := false.B
  roundRawFNToRecFN.io.in.isNaN := buffer2.io.deq.bits.roundMetadata(expWidth + 8)
  roundRawFNToRecFN.io.in.isInf := buffer2.io.deq.bits.roundMetadata(expWidth + 7)
  roundRawFNToRecFN.io.in.isZero := buffer2.io.deq.bits.roundMetadata(expWidth + 6)
  roundRawFNToRecFN.io.in.sign := buffer2.io.deq.bits.roundMetadata(expWidth + 5)
  roundRawFNToRecFN.io.in.sExp := buffer2.io.deq.bits.roundMetadata(expWidth + 4, 3).asSInt
  roundRawFNToRecFN.io.in.sig := buffer2.io.deq.bits.outSig
  roundRawFNToRecFN.io.roundingMode := buffer2.io.deq.bits.roundMetadata(2, 0)
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.resp.bits.out := roundRawFNToRecFN.io.out
  io.resp.bits.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
  io.resp.valid := buffer2.io.deq.valid
  buffer2.io.deq.ready := io.resp.ready
}
