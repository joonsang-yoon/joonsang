package HardInt

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import freechips.rocketchip.rocket.DecodeLogic
import freechips.rocketchip.rocket.constants.ScalarOpConstants
import HardUtils._
import HardInt.ALU._

class PreMultiplierStageInput(dataWidth: Int, tagWidth: Int) extends Bundle with ScalarOpConstants {
  val fn = UInt(SZ_ALU_FN.W)
  val dw = UInt(SZ_DW.W)
  val in1 = UInt(dataWidth.W)
  val in2 = UInt(dataWidth.W)
  val tag = UInt(tagWidth.W)
}

class MultiplierStage1Input(dataWidth: Int) extends Bundle {
  val isMultiplicandSigned = Bool()
  val isMultiplierSigned = Bool()
  val multiplicand = UInt(dataWidth.W)
  val multiplier = UInt(dataWidth.W)
}

class MultiplierStage1ToStage2(dataWidth: Int) extends Bundle {
  val partialProductColumns = Vec(2 * dataWidth, Vec(dataWidth, Bool()))
}

class MultiplierStage2Output(dataWidth: Int) extends Bundle {
  val product = UInt((2 * dataWidth).W)
}

class PostMultiplierStageOutput(dataWidth: Int, tagWidth: Int) extends Bundle {
  val out = UInt(dataWidth.W)
  val tag = UInt(tagWidth.W)
}

class MultiplierMetadata(tagWidth: Int) extends Bundle {
  val isHighWord = Bool()
  val isHalfWidth = Bool()
  val tag = UInt(tagWidth.W)
}

class PreMultiplierStage(dataWidth: Int, tagWidth: Int) extends RawModule with ScalarOpConstants {
  override def desiredName: String = s"PreMultiplierStage_dw${dataWidth}"

  val io = IO(new Bundle {
    val in = Input(new PreMultiplierStageInput(dataWidth, tagWidth))
    val out = Output(new Bundle {
      val data = new MultiplierStage1Input(dataWidth)
      val metadata = new MultiplierMetadata(tagWidth)
    })
  })

  val decodeTable = List(
    FN_MUL -> List(N, X, X),
    FN_MULH -> List(Y, Y, Y),
    FN_MULHSU -> List(Y, Y, N),
    FN_MULHU -> List(Y, N, N)
  )
  val isHighWord :: isMultiplicandSigned :: isMultiplierSigned :: Nil =
    DecodeLogic(io.in.fn, List(X, X, X), decodeTable).map(_.asBool)

  val isHalfWidth = (dataWidth > 32).B && (io.in.dw === DW_32)

  io.out.data.isMultiplicandSigned := isMultiplicandSigned
  io.out.data.isMultiplierSigned := isMultiplierSigned
  io.out.data.multiplicand := io.in.in1
  io.out.data.multiplier := io.in.in2
  io.out.metadata.isHighWord := isHighWord
  io.out.metadata.isHalfWidth := isHalfWidth
  io.out.metadata.tag := io.in.tag
}

class MultiplierStage1(dataWidth: Int, initHeight: Int) extends RawModule {
  override def desiredName: String = s"MultiplierStage1_dw${dataWidth}_initHeight${initHeight}"

  val io = IO(new Bundle {
    val in = Input(new MultiplierStage1Input(dataWidth))
    val out = Output(new MultiplierStage1ToStage2(dataWidth))
  })

  val multiplicandSign = io.in.isMultiplicandSigned && io.in.multiplicand(dataWidth - 1)
  val multiplierSign = io.in.isMultiplierSigned && io.in.multiplier(dataWidth - 1)

  val numBoothGroups = (dataWidth >> 1) + 1
  val boothExtendedMultiplier =
    Cat(Replicate(2 * numBoothGroups - dataWidth, multiplierSign), io.in.multiplier, false.B)

  val boothEncodingPos0 = BitPat("b000") // Use +0 * Multiplicand (isNeg=0, Mag=0)
  val boothEncodingPos1 = BitPat("b001") // Use +1 * Multiplicand (isNeg=0, Mag=1)
  val boothEncodingPos2 = BitPat("b010") // Use +2 * Multiplicand (isNeg=0, Mag=2)
  val boothEncodingNeg2 = BitPat("b110") // Use -2 * Multiplicand (isNeg=1, Mag=2)
  val boothEncodingNeg1 = BitPat("b101") // Use -1 * Multiplicand (isNeg=1, Mag=1)
  val boothEncodingNeg0 = BitPat("b100") // Use -0 * Multiplicand (isNeg=1, Mag=0)

  var partialProductColumns = Array.fill(2 * dataWidth)(Seq.empty[Bool])

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

    val partialProduct = Wire(Vec(dataWidth + 1, Bool()))
    for (j <- partialProduct.indices) {
      partialProduct(j) := (j match {
        case 0 =>
          ((io.in.multiplicand(0) && isMag1) || (false.B && isMag2)) ^ isNeg
        case x if (x == dataWidth) =>
          ((multiplicandSign && isMag1) || (io.in.multiplicand(dataWidth - 1) && isMag2)) ^ isNeg
        case _ =>
          ((io.in.multiplicand(j) && isMag1) || (io.in.multiplicand(j - 1) && isMag2)) ^ isNeg
      })
    }

    val partialProductSign = ((multiplicandSign && isMag1) || (multiplicandSign && isMag2)) ^ isNeg
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
    partialProductColumns.toSeq.map(col => VecInit(col.padTo(dataWidth, false.B)))
  )

  def getColumnSizes: Seq[Int] = partialProductColumns.toSeq.map(_.size)
}

class MultiplierStage2(dataWidth: Int, columnSizes: Seq[Int]) extends RawModule {
  override def desiredName: String = s"MultiplierStage2_dw${dataWidth}_maxCol${columnSizes.max}"

  val io = IO(new Bundle {
    val in = Input(new MultiplierStage1ToStage2(dataWidth))
    val out = Output(new MultiplierStage2Output(dataWidth))
  })

  var partialProductColumns = Array.fill(2 * dataWidth)(Seq.empty[Bool])
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

  io.out.product := FinalAdder(partialProductColumns)
}

class PostMultiplierStage(dataWidth: Int, tagWidth: Int) extends RawModule {
  override def desiredName: String = s"PostMultiplierStage_dw${dataWidth}"

  val io = IO(new Bundle {
    val in = Input(new Bundle {
      val data = new MultiplierStage2Output(dataWidth)
      val metadata = new MultiplierMetadata(tagWidth)
    })
    val out = Output(new PostMultiplierStageOutput(dataWidth, tagWidth))
  })

  val halfWidth = dataWidth >> 1

  val finalOut = Mux(
    io.in.metadata.isHighWord,
    io.in.data.product(2 * dataWidth - 1, dataWidth),
    Cat(
      Mux(
        io.in.metadata.isHalfWidth,
        Replicate(halfWidth, io.in.data.product(halfWidth - 1)),
        io.in.data.product(dataWidth - 1, halfWidth)
      ),
      io.in.data.product(halfWidth - 1, 0)
    )
  )

  io.out.out := finalOut
  io.out.tag := io.in.metadata.tag
}

class Radix4BoothMultiplier[T <: Data](dataWidth: Int, initHeight: Int, metadataGen: () => T) extends Module {
  override def desiredName: String = s"Radix4BoothMultiplier_dw${dataWidth}_initHeight${initHeight}"

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val data = new MultiplierStage1Input(dataWidth)
      val metadata = metadataGen()
    }))
    val resp = Decoupled(new Bundle {
      val data = new MultiplierStage2Output(dataWidth)
      val metadata = metadataGen()
    })
  })

  class Stage1ToStage2 extends Bundle {
    val data = new MultiplierStage1ToStage2(dataWidth)
    val metadata = metadataGen()
  }

  val stage1 = Module(new MultiplierStage1(dataWidth, initHeight))
  val columnSizes = stage1.getColumnSizes
  val buffer1 = Module(new SkidBuffer(new Stage1ToStage2, columnSizes.max > 2))
  val stage2 = Module(new MultiplierStage2(dataWidth, columnSizes))

  stage1.io.in := io.req.bits.data
  buffer1.io.enq.bits.data := stage1.io.out
  buffer1.io.enq.bits.metadata := io.req.bits.metadata
  buffer1.io.enq.valid := io.req.valid
  io.req.ready := buffer1.io.enq.ready

  stage2.io.in := buffer1.io.deq.bits.data
  io.resp.bits.data := stage2.io.out
  io.resp.bits.metadata := buffer1.io.deq.bits.metadata
  io.resp.valid := buffer1.io.deq.valid
  buffer1.io.deq.ready := io.resp.ready
}

object Radix4BoothMultiplier {
  def apply[T <: Data](dataWidth: Int, initHeight: Int, metadataGen: () => T): Radix4BoothMultiplier[T] = {
    new Radix4BoothMultiplier(dataWidth, initHeight, metadataGen)
  }

  def apply(dataWidth: Int, initHeight: Int): Radix4BoothMultiplier[Bundle] = {
    new Radix4BoothMultiplier(dataWidth, initHeight, () => new Bundle {})
  }
}

class RISCVMultiplier(dataWidth: Int = 64, numXPRs: Int = 32, initHeight: Int = 3) extends Module {
  override def desiredName: String = s"RISCVMultiplier_dw${dataWidth}_initHeight${initHeight}"

  val tagWidth = log2Ceil(numXPRs)

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreMultiplierStageInput(dataWidth, tagWidth)))
    val resp = Decoupled(new PostMultiplierStageOutput(dataWidth, tagWidth))
  })

  val preStage = Module(new PreMultiplierStage(dataWidth, tagWidth))
  val coreMul = Module(new Radix4BoothMultiplier(dataWidth, initHeight, () => new MultiplierMetadata(tagWidth)))
  val postStage = Module(new PostMultiplierStage(dataWidth, tagWidth))

  preStage.io.in := io.req.bits
  coreMul.io.req.bits.data := preStage.io.out.data
  coreMul.io.req.bits.metadata := preStage.io.out.metadata
  coreMul.io.req.valid := io.req.valid
  io.req.ready := coreMul.io.req.ready

  postStage.io.in.data := coreMul.io.resp.bits.data
  postStage.io.in.metadata := coreMul.io.resp.bits.metadata
  io.resp.bits := postStage.io.out
  io.resp.valid := coreMul.io.resp.valid
  coreMul.io.resp.ready := io.resp.ready
}
