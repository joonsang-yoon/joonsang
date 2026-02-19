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
  val roundMetadata = UInt((expWidth + 10).W)
}

class MulStage1ToStage2(expWidth: Int, sigWidth: Int) extends Bundle {
  val partialProductColumns = Vec(2 * sigWidth, Vec(sigWidth, Bool()))
  val roundMetadata = UInt((expWidth + 10).W)
}

class MulStage2Output(expWidth: Int, sigWidth: Int) extends Bundle {
  val outSig = UInt((sigWidth + 3).W)
  val roundMetadata = UInt((expWidth + 10).W)
}

class PostMulStageOutput(expWidth: Int, sigWidth: Int) extends Bundle {
  val out = UInt((expWidth + sigWidth + 1).W)
  val exceptionFlags = UInt(5.W)
}

class PreMulStage(expWidth: Int, sigWidth: Int) extends RawModule {
  override def desiredName: String = s"PreMulStage_ew${expWidth}_sw${sigWidth}"

  val io = IO(new Bundle {
    val in = Input(new PreMulStageInput(expWidth, sigWidth))
    val out = Output(new MulStage1Input(expWidth, sigWidth))
  })

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.in.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.in.b)

  val invalidExc = IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) ||
    (rawA.isInf && rawB.isZero) || (rawA.isZero && rawB.isInf)
  val isNaNOut = rawA.isNaN || rawB.isNaN
  val notNaN_isInfOut = rawA.isInf || rawB.isInf
  val notNaN_isZeroOut = rawA.isZero || rawB.isZero
  val notNaN_signOut = rawA.sign ^ rawB.sign

  val bSExpMinusOffset = Cat(~rawB.sExp(expWidth), rawB.sExp(expWidth - 1, 0)).asSInt
  val common_sExpOut = rawA.sExp(expWidth, 0).pad(expWidth + 2).asSInt + bSExpMinusOffset

  io.out.in1Sig := rawA.sig(sigWidth - 2, 0)
  io.out.in2Sig := rawB.sig(sigWidth - 2, 0)
  io.out.roundMetadata := Cat(
    invalidExc,
    isNaNOut,
    notNaN_isInfOut,
    notNaN_isZeroOut,
    notNaN_signOut,
    common_sExpOut,
    io.in.roundingMode
  )
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
  io.out.roundMetadata := io.in.roundMetadata

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

  val mulResult = FinalAdder(partialProductColumns)

  io.out.outSig := Cat(mulResult(2 * sigWidth - 1, sigWidth - 2), mulResult(sigWidth - 3, 0).orR)
  io.out.roundMetadata := io.in.roundMetadata
}

class MulRecFN(expWidth: Int, sigWidth: Int, initHeight: Int) extends Module {
  override def desiredName: String = s"MulRecFN_ew${expWidth}_sw${sigWidth}_initHeight${initHeight}"

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreMulStageInput(expWidth, sigWidth)))
    val detectTininess = Input(Bool())
    val resp = Decoupled(new PostMulStageOutput(expWidth, sigWidth))
  })

  val preStage = Module(new PreMulStage(expWidth, sigWidth))
  val stage1 = Module(new MulStage1(expWidth, sigWidth, initHeight))
  val columnSizes = stage1.getColumnSizes
  val buffer1 = Module(new SkidBuffer(new MulStage1ToStage2(expWidth, sigWidth), columnSizes.max > 2))
  val stage2 = Module(new MulStage2(expWidth, sigWidth, columnSizes))
  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))

  preStage.io.in := io.req.bits
  stage1.io.in := preStage.io.out
  buffer1.io.enq.bits := stage1.io.out
  buffer1.io.enq.valid := io.req.valid
  io.req.ready := buffer1.io.enq.ready

  stage2.io.in := buffer1.io.deq.bits
  roundRawFNToRecFN.io.invalidExc := stage2.io.out.roundMetadata(expWidth + 9)
  roundRawFNToRecFN.io.infiniteExc := false.B
  roundRawFNToRecFN.io.in.isNaN := stage2.io.out.roundMetadata(expWidth + 8)
  roundRawFNToRecFN.io.in.isInf := stage2.io.out.roundMetadata(expWidth + 7)
  roundRawFNToRecFN.io.in.isZero := stage2.io.out.roundMetadata(expWidth + 6)
  roundRawFNToRecFN.io.in.sign := stage2.io.out.roundMetadata(expWidth + 5)
  roundRawFNToRecFN.io.in.sExp := stage2.io.out.roundMetadata(expWidth + 4, 3).asSInt
  roundRawFNToRecFN.io.in.sig := stage2.io.out.outSig
  roundRawFNToRecFN.io.roundingMode := stage2.io.out.roundMetadata(2, 0)
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.resp.bits.out := roundRawFNToRecFN.io.out
  io.resp.bits.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
  io.resp.valid := buffer1.io.deq.valid
  buffer1.io.deq.ready := io.resp.ready
}
