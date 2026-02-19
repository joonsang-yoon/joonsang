package HardFloat

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import HardUtils._

class PreMulStageInput(expWidth: Int, sigWidth: Int) extends Bundle {
  val a = UInt((expWidth + sigWidth + 1).W)
  val b = UInt((expWidth + sigWidth + 1).W)
  val roundingMode = UInt(3.W)
}

class MulStage1Input(expWidth: Int, sigWidth: Int) extends Bundle {
  val in1Sig = UInt((sigWidth - 1).W)
  val in2Sig = UInt((sigWidth - 1).W)
  val metadata = UInt((expWidth + 10).W)
}

class MulStage1ToStage2(expWidth: Int, sigWidth: Int) extends Bundle {
  val partialProductColumns = Vec(2 * sigWidth, Vec(sigWidth, Bool()))
  val metadata = UInt((expWidth + 10).W)
}

class MulStage2Output(expWidth: Int, sigWidth: Int) extends Bundle {
  val outSig = UInt((2 * sigWidth).W)
  val metadata = UInt((expWidth + 10).W)
}

class PostMulStageOutput(expWidth: Int, sigWidth: Int) extends Bundle {
  val out = UInt((expWidth + sigWidth + 1).W)
  val exceptionFlags = UInt(5.W)
}

class MulStage1(expWidth: Int, sigWidth: Int, initHeight: Int) extends RawModule {
  override def desiredName: String = s"MulStage1_ew${expWidth}_sw${sigWidth}_initHeight${initHeight}"

  val io = IO(new Bundle {
    val in = Input(new MulStage1Input(expWidth, sigWidth))
    val out = Output(new MulStage1ToStage2(expWidth, sigWidth))
  })

  val numBoothGroups = (sigWidth >> 1) + 1
  val boothExtendedMultiplier = Cat(0.U((2 * numBoothGroups - sigWidth).W), true.B, io.in.in2Sig, false.B)

  val boothEncodingPos0 = BitPat("b000") // Use +0 * Multiplicand (isNeg=0, Mag=0)
  val boothEncodingPos1 = BitPat("b001") // Use +1 * Multiplicand (isNeg=0, Mag=1)
  val boothEncodingPos2 = BitPat("b010") // Use +2 * Multiplicand (isNeg=0, Mag=2)
  val boothEncodingNeg2 = BitPat("b110") // Use -2 * Multiplicand (isNeg=1, Mag=2)
  val boothEncodingNeg1 = BitPat("b101") // Use -1 * Multiplicand (isNeg=1, Mag=1)
  val boothEncodingNeg0 = BitPat("b100") // Use -0 * Multiplicand (isNeg=1, Mag=0)

  var partialProductColumns = Array.fill(2 * sigWidth)(Seq.empty[Bool])

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
        case x if (x == sigWidth - 1) =>
          ((true.B && isMag1) || (io.in.in1Sig(sigWidth - 2) && isMag2)) ^ isNeg
        case x if (x == sigWidth) =>
          ((false.B && isMag1) || (true.B && isMag2)) ^ isNeg
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
  io.out.metadata := io.in.metadata

  def getColumnSizes: Seq[Int] = partialProductColumns.toSeq.map(_.size)
}

class MulStage2(expWidth: Int, sigWidth: Int, columnSizes: Seq[Int]) extends RawModule {
  override def desiredName: String = s"MulStage2_ew${expWidth}_sw${sigWidth}_maxCol${columnSizes.max}"

  val io = IO(new Bundle {
    val in = Input(new MulStage1ToStage2(expWidth, sigWidth))
    val out = Output(new MulStage2Output(expWidth, sigWidth))
  })

  var partialProductColumns = Array.fill(2 * sigWidth)(Seq.empty[Bool])
  for (i <- partialProductColumns.indices) {
    if (i < io.in.partialProductColumns.size) {
      partialProductColumns(i) = partialProductColumns(i) ++ io.in.partialProductColumns(i).take(columnSizes(i)).toSeq
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

  io.out.outSig := FinalAdder(partialProductColumns)
  io.out.metadata := io.in.metadata
}

class MulRecFN(expWidth: Int, sigWidth: Int, initHeight: Int) extends Module {
  override def desiredName: String = s"MulRecFN_ew${expWidth}_sw${sigWidth}_initHeight${initHeight}"

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreMulStageInput(expWidth, sigWidth)))
    val detectTininess = Input(Bool())
    val resp = Decoupled(new PostMulStageOutput(expWidth, sigWidth))
  })

  val stage1 = Module(new MulStage1(expWidth, sigWidth, initHeight))
  val columnSizes = stage1.getColumnSizes

  val preStageIn = io.req.bits

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, preStageIn.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, preStageIn.b)

  val invalidExc_pre = IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) ||
    (rawA.isInf && rawB.isZero) || (rawA.isZero && rawB.isInf)
  val isNaNOut_pre = rawA.isNaN || rawB.isNaN
  val notNaN_isInfOut_pre = rawA.isInf || rawB.isInf
  val notNaN_isZeroOut_pre = rawA.isZero || rawB.isZero
  val notNaN_signOut_pre = rawA.sign ^ rawB.sign

  val bSExpMinusOffset = Cat(~rawB.sExp(expWidth), rawB.sExp(expWidth - 1, 0)).asSInt
  val common_sExpOut_pre = rawA.sExp(expWidth, 0).pad(expWidth + 2).asSInt + bSExpMinusOffset

  val roundingMode_pre = preStageIn.roundingMode

  val metadata = Cat(
    invalidExc_pre,
    isNaNOut_pre,
    notNaN_isInfOut_pre,
    notNaN_isZeroOut_pre,
    notNaN_signOut_pre,
    common_sExpOut_pre,
    roundingMode_pre
  )

  stage1.io.in.in1Sig := rawA.sig(sigWidth - 2, 0)
  stage1.io.in.in2Sig := rawB.sig(sigWidth - 2, 0)
  stage1.io.in.metadata := metadata

  if (columnSizes.max > 2) {
    val buffer1 = Module(new SkidBuffer(new MulStage1ToStage2(expWidth, sigWidth)))
    val stage2 = Module(new MulStage2(expWidth, sigWidth, columnSizes))

    buffer1.io.enq.bits := stage1.io.out
    buffer1.io.enq.valid := io.req.valid
    io.req.ready := buffer1.io.enq.ready

    stage2.io.in := buffer1.io.deq.bits
    val postStageIn = stage2.io.out

    val invalidExc_post = postStageIn.metadata(expWidth + 9)
    val isNaNOut_post = postStageIn.metadata(expWidth + 8)
    val notNaN_isInfOut_post = postStageIn.metadata(expWidth + 7)
    val notNaN_isZeroOut_post = postStageIn.metadata(expWidth + 6)
    val notNaN_signOut_post = postStageIn.metadata(expWidth + 5)
    val common_sExpOut_post = postStageIn.metadata(expWidth + 4, 3).asSInt
    val common_sigOut_post =
      Cat(postStageIn.outSig(2 * sigWidth - 1, sigWidth - 2), postStageIn.outSig(sigWidth - 3, 0).orR)
    val roundingMode_post = postStageIn.metadata(2, 0)

    val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    roundRawFNToRecFN.io.invalidExc := invalidExc_post
    roundRawFNToRecFN.io.infiniteExc := false.B
    roundRawFNToRecFN.io.in.isNaN := isNaNOut_post
    roundRawFNToRecFN.io.in.isInf := notNaN_isInfOut_post
    roundRawFNToRecFN.io.in.isZero := notNaN_isZeroOut_post
    roundRawFNToRecFN.io.in.sign := notNaN_signOut_post
    roundRawFNToRecFN.io.in.sExp := common_sExpOut_post
    roundRawFNToRecFN.io.in.sig := common_sigOut_post
    roundRawFNToRecFN.io.roundingMode := roundingMode_post
    roundRawFNToRecFN.io.detectTininess := io.detectTininess
    io.resp.bits.out := roundRawFNToRecFN.io.out
    io.resp.bits.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
    io.resp.valid := buffer1.io.deq.valid
    buffer1.io.deq.ready := io.resp.ready
  } else {
    val stage2 = Module(new MulStage2(expWidth, sigWidth, columnSizes))

    stage2.io.in := stage1.io.out
    val postStageIn = stage2.io.out

    val invalidExc_post = postStageIn.metadata(expWidth + 9)
    val isNaNOut_post = postStageIn.metadata(expWidth + 8)
    val notNaN_isInfOut_post = postStageIn.metadata(expWidth + 7)
    val notNaN_isZeroOut_post = postStageIn.metadata(expWidth + 6)
    val notNaN_signOut_post = postStageIn.metadata(expWidth + 5)
    val common_sExpOut_post = postStageIn.metadata(expWidth + 4, 3).asSInt
    val common_sigOut_post =
      Cat(postStageIn.outSig(2 * sigWidth - 1, sigWidth - 2), postStageIn.outSig(sigWidth - 3, 0).orR)
    val roundingMode_post = postStageIn.metadata(2, 0)

    val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
    roundRawFNToRecFN.io.invalidExc := invalidExc_post
    roundRawFNToRecFN.io.infiniteExc := false.B
    roundRawFNToRecFN.io.in.isNaN := isNaNOut_post
    roundRawFNToRecFN.io.in.isInf := notNaN_isInfOut_post
    roundRawFNToRecFN.io.in.isZero := notNaN_isZeroOut_post
    roundRawFNToRecFN.io.in.sign := notNaN_signOut_post
    roundRawFNToRecFN.io.in.sExp := common_sExpOut_post
    roundRawFNToRecFN.io.in.sig := common_sigOut_post
    roundRawFNToRecFN.io.roundingMode := roundingMode_post
    roundRawFNToRecFN.io.detectTininess := io.detectTininess
    io.resp.bits.out := roundRawFNToRecFN.io.out
    io.resp.bits.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
    io.resp.valid := io.req.valid
    io.req.ready := io.resp.ready
  }
}
