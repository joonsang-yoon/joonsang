package HardFloat

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import HardUtils._

class PreDivSqrtStageInput(expWidth: Int, sigWidth: Int) extends Bundle {
  val sqrtOp = Bool()
  val a = UInt((expWidth + sigWidth + 1).W)
  val b = UInt((expWidth + sigWidth + 1).W)
  val roundingMode = UInt(3.W)
}

class DivSqrtStage1Input(expWidth: Int, sigWidth: Int) extends Bundle {
  val normalCase = Bool()
  val sqrtOp = Bool()
  val isSExpOdd_sqrt = Bool()
  val in1Sig = UInt((sigWidth - 1).W)
  val in2Sig = UInt((sigWidth - 1).W)
  val metadata = UInt((expWidth + 10).W)
}

class DivSqrtStage1ToStage2(expWidth: Int, sigWidth: Int, accResWidth: Int) extends Bundle {
  val sqrtOp = Bool()
  val trialDivisor = UInt((sigWidth - 1).W)
  val residualSum = UInt((sigWidth + 3).W)
  val residualCarry = UInt(sigWidth.W)
  val accRes = UInt(accResWidth.W)
  val accResMinusUlp = UInt(accResWidth.W)
  val metadata = UInt((expWidth + 10).W)
}

class DivSqrtStage2ToStage3(expWidth: Int, sigWidth: Int, accResWidth: Int) extends Bundle {
  val residualSum = UInt((accResWidth + 2).W)
  val residualCarry = UInt(sigWidth.W)
  val provisionalResult = UInt((accResWidth + 1).W)
  val metadata = UInt((expWidth + 10).W)
}

class DivSqrtStage3Output(expWidth: Int, sigWidth: Int) extends Bundle {
  val outSig = UInt((sigWidth + 3).W)
  val metadata = UInt((expWidth + 10).W)
}

class PostDivSqrtStageOutput(expWidth: Int, sigWidth: Int) extends Bundle {
  val out = UInt((expWidth + sigWidth + 1).W)
  val exceptionFlags = UInt(5.W)
}

case class ResultDigitSelectionRangeConfig(
  pos2Range: (Int, Int),
  pos1Range: (Int, Int),
  zeroRange: (Int, Int),
  neg1Range: (Int, Int),
  neg2Range: (Int, Int)
)

object ResultDigitSelector {
  val resultDigitEncodingPos2 = BitPat("b110")
  val resultDigitEncodingPos1 = BitPat("b101")
  val resultDigitEncodingZero = BitPat("b?00")
  val resultDigitEncodingNeg1 = BitPat("b001")
  val resultDigitEncodingNeg2 = BitPat("b010")

  val resultDigitSelectionRanges: Seq[ResultDigitSelectionRangeConfig] = Seq(
    ResultDigitSelectionRangeConfig((12, 24), (4, 11), (-4, 3), (-13, -5), (-25, -14)),
    ResultDigitSelectionRangeConfig((14, 26), (4, 13), (-4, 3), (-14, -5), (-28, -15)),
    ResultDigitSelectionRangeConfig((16, 29), (4, 15), (-6, 3), (-16, -7), (-30, -17)),
    ResultDigitSelectionRangeConfig((16, 32), (4, 15), (-6, 3), (-17, -7), (-33, -18)),
    ResultDigitSelectionRangeConfig((18, 35), (6, 17), (-6, 5), (-18, -7), (-36, -19)),
    ResultDigitSelectionRangeConfig((20, 37), (8, 19), (-8, 7), (-20, -9), (-38, -21)),
    ResultDigitSelectionRangeConfig((20, 40), (8, 19), (-8, 7), (-22, -9), (-41, -23)),
    ResultDigitSelectionRangeConfig((24, 42), (8, 23), (-8, 7), (-24, -9), (-44, -25))
  )

  private def encodeLutInput(truncTrialDiv: Int, estResid: Int): BitPat = {
    val lutInput = (truncTrialDiv << 7) | (estResid & 0x7f)
    BitPat(lutInput.U(10.W))
  }

  private def buildLutEntries(
    truncTrialDiv: Int,
    rangeConfig:   ResultDigitSelectionRangeConfig
  ): Seq[(BitPat, BitPat)] = {
    val rangeMappings = Seq(
      rangeConfig.pos2Range -> resultDigitEncodingPos2,
      rangeConfig.pos1Range -> resultDigitEncodingPos1,
      rangeConfig.zeroRange -> resultDigitEncodingZero,
      rangeConfig.neg1Range -> resultDigitEncodingNeg1,
      rangeConfig.neg2Range -> resultDigitEncodingNeg2
    )

    rangeMappings.flatMap { case ((min, max), resultDigitEncoding) =>
      (min to max).map { estResid =>
        encodeLutInput(truncTrialDiv, estResid) -> resultDigitEncoding
      }
    }
  }

  lazy val lutEntries: Map[BitPat, BitPat] = {
    resultDigitSelectionRanges.zipWithIndex.flatMap { case (rangeConfig, truncTrialDiv) =>
      buildLutEntries(truncTrialDiv, rangeConfig)
    }.toMap
  }
}

class ResultDigitSelector extends RawModule {
  val io = IO(new Bundle {
    val truncatedTrialDivisor = Input(UInt(3.W))
    val truncatedResidualSum = Input(UInt(8.W))
    val truncatedResidualCarry = Input(UInt(8.W))
    val isNeg = Output(Bool())
    val isMag2 = Output(Bool())
    val isMag1 = Output(Bool())
  })

  val estimatedResidual = io.truncatedResidualSum + io.truncatedResidualCarry

  val resultDigitEncoding = decoder(
    minimizer = EspressoMinimizer,
    input = Cat(io.truncatedTrialDivisor, estimatedResidual(7, 1)),
    truthTable = TruthTable(
      table = ResultDigitSelector.lutEntries,
      default = BitPat.dontCare(3)
    )
  )

  io.isNeg := resultDigitEncoding(2)
  io.isMag2 := resultDigitEncoding(1)
  io.isMag1 := resultDigitEncoding(0)
}

class DivSqrtStage1(expWidth: Int, sigWidth: Int, accResWidth: Int, iterationWidth: Int, resultShamtWidth: Int)
    extends RawModule {
  override def desiredName: String = s"DivSqrtStage1_ew${expWidth}_sw${sigWidth}"

  val io = IO(new Bundle {
    val in = Input(new DivSqrtStage1Input(expWidth, sigWidth))
    val out = Output(new DivSqrtStage1ToStage2(expWidth, sigWidth, accResWidth))
    val totalIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

  val initialResidualSum = Mux(
    io.in.isSExpOdd_sqrt,
    Cat("b111".U(3.W), io.in.in1Sig, false.B),
    Cat("b1101".U(4.W), io.in.in1Sig)
  )

  val secondAccRes = decoder(
    minimizer = QMCMinimizer,
    input = initialResidualSum(sigWidth, sigWidth - 5),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b1111??") -> BitPat("b10000"),
        BitPat("b1110??") -> BitPat("b01111"),
        BitPat("b1101??") -> BitPat("b01111"),
        BitPat("b1100??") -> BitPat("b01110"),
        BitPat("b10111?") -> BitPat("b01110"),
        BitPat("b10110?") -> BitPat("b01101"),
        BitPat("b1010??") -> BitPat("b01101"),
        BitPat("b100111") -> BitPat("b01101"),
        BitPat("b100110") -> BitPat("b01100"),
        BitPat("b10010?") -> BitPat("b01100"),
        BitPat("b10001?") -> BitPat("b01100"),
        BitPat("b100001") -> BitPat("b01100"),
        BitPat("b100000") -> BitPat("b01011"),
        BitPat("b0111??") -> BitPat("b01011"),
        BitPat("b0110??") -> BitPat("b01010"),
        BitPat("b01011?") -> BitPat("b01010"),
        BitPat("b01010?") -> BitPat("b01001"),
        BitPat("b01001?") -> BitPat("b01001"),
        BitPat("b01000?") -> BitPat("b01000")
      ),
      default = BitPat.dontCare(5)
    )
  )
  val secondAccResMinusUlp = decoder(
    minimizer = QMCMinimizer,
    input = initialResidualSum(sigWidth, sigWidth - 5),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b1111??") -> BitPat("b01111"),
        BitPat("b1110??") -> BitPat("b01110"),
        BitPat("b1101??") -> BitPat("b01110"),
        BitPat("b1100??") -> BitPat("b01101"),
        BitPat("b10111?") -> BitPat("b01101"),
        BitPat("b10110?") -> BitPat("b01100"),
        BitPat("b1010??") -> BitPat("b01100"),
        BitPat("b100111") -> BitPat("b01100"),
        BitPat("b100110") -> BitPat("b01011"),
        BitPat("b10010?") -> BitPat("b01011"),
        BitPat("b10001?") -> BitPat("b01011"),
        BitPat("b100001") -> BitPat("b01011"),
        BitPat("b100000") -> BitPat("b01010"),
        BitPat("b0111??") -> BitPat("b01010"),
        BitPat("b0110??") -> BitPat("b01001"),
        BitPat("b01011?") -> BitPat("b01001"),
        BitPat("b01010?") -> BitPat("b01000"),
        BitPat("b01001?") -> BitPat("b01000"),
        BitPat("b01000?") -> BitPat("b00111")
      ),
      default = BitPat.dontCare(5)
    )
  )

  val secondResidualSum = initialResidualSum(sigWidth - 2, 0)
  val secondResidualCarry = decoder(
    minimizer = QMCMinimizer,
    input = initialResidualSum(sigWidth, sigWidth - 5),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b1111??") -> BitPat("b000000"),
        BitPat("b1110??") -> BitPat("b011111"),
        BitPat("b1101??") -> BitPat("b011111"),
        BitPat("b1100??") -> BitPat("b111100"),
        BitPat("b10111?") -> BitPat("b111100"),
        BitPat("b10110?") -> BitPat("b010111"),
        BitPat("b1010??") -> BitPat("b010111"),
        BitPat("b100111") -> BitPat("b010111"),
        BitPat("b100110") -> BitPat("b110000"),
        BitPat("b10010?") -> BitPat("b110000"),
        BitPat("b10001?") -> BitPat("b110000"),
        BitPat("b100001") -> BitPat("b110000"),
        BitPat("b100000") -> BitPat("b000111"),
        BitPat("b0111??") -> BitPat("b000111"),
        BitPat("b0110??") -> BitPat("b011100"),
        BitPat("b01011?") -> BitPat("b011100"),
        BitPat("b01010?") -> BitPat("b101111"),
        BitPat("b01001?") -> BitPat("b101111"),
        BitPat("b01000?") -> BitPat("b000000")
      ),
      default = BitPat.dontCare(6)
    )
  )

  io.out.sqrtOp := io.in.sqrtOp
  io.out.trialDivisor := Mux(
    io.in.sqrtOp,
    Cat(
      secondAccRes(2, 0) ^ Replicate(3, !secondAccRes(3)),
      0.U((sigWidth - 4 - resultShamtWidth).W),
      1.U(resultShamtWidth.W)
    ),
    io.in.in2Sig
  )
  io.out.residualSum := Mux(
    io.in.sqrtOp,
    Cat(secondResidualSum, 0.U(4.W)),
    Cat("b0001".U(4.W), io.in.in1Sig)
  )
  io.out.residualCarry := Mux(
    io.in.sqrtOp,
    Cat(secondResidualCarry, 0.U((sigWidth - 6).W)),
    0.U(sigWidth.W)
  )
  io.out.accRes := Mux(
    io.in.sqrtOp,
    Cat(0.U((accResWidth - 5).W), secondAccRes),
    0.U(accResWidth.W)
  )
  io.out.accResMinusUlp := Mux(
    io.in.sqrtOp,
    Cat(0.U((accResWidth - 5).W), secondAccResMinusUlp),
    (-1.S(accResWidth.W)).asUInt
  )
  io.out.metadata := io.in.metadata

  io.totalIterationsMinus2 := Mux(
    io.in.normalCase,
    Mux(
      io.in.sqrtOp,
      ((sigWidth >> 1) - 3).U(iterationWidth.W),
      (sigWidth >> 1).U(iterationWidth.W)
    ),
    (-1.S(iterationWidth.W)).asUInt
  )
}

class DivSqrtStage2(expWidth: Int, sigWidth: Int, accResWidth: Int, iterationWidth: Int, resultShamtWidth: Int)
    extends RawModule {
  override def desiredName: String = s"DivSqrtStage2_ew${expWidth}_sw${sigWidth}"

  val io = IO(new Bundle {
    val in = Input(new DivSqrtStage1ToStage2(expWidth, sigWidth, accResWidth))
    val remainingIterationsMinus2 = Input(UInt(iterationWidth.W))
    val out = Output(new DivSqrtStage2ToStage3(expWidth, sigWidth, accResWidth))
    val feedbackData = Output(new DivSqrtStage1ToStage2(expWidth, sigWidth, accResWidth))
  })

  val rds = Module(new ResultDigitSelector)
  rds.io.truncatedTrialDivisor := io.in.trialDivisor(sigWidth - 2, sigWidth - 4)
  rds.io.truncatedResidualSum := io.in.residualSum(sigWidth + 2, sigWidth - 5)
  rds.io.truncatedResidualCarry := Cat(io.in.residualCarry, 0.U(3.W))(sigWidth + 2, sigWidth - 5)

  val isNeg = rds.io.isNeg
  val isMag2 = rds.io.isMag2
  val isMag1 = rds.io.isMag1

  val divResidualAddend = Wire(Vec(sigWidth + 1, Bool()))
  for (i <- divResidualAddend.indices) {
    divResidualAddend(i) := (i match {
      case 0 =>
        ((io.in.trialDivisor(0) && isMag1) || (false.B && isMag2)) ^ isNeg
      case x if (x == sigWidth - 1) =>
        ((true.B && isMag1) || (io.in.trialDivisor(sigWidth - 2) && isMag2)) ^ isNeg
      case x if (x == sigWidth) =>
        ((false.B && isMag1) || (true.B && isMag2)) ^ isNeg
      case _ =>
        ((io.in.trialDivisor(i) && isMag1) || (io.in.trialDivisor(i - 1) && isMag2)) ^ isNeg
    })
  }
  val sqrtResidualAddend = Wire(Vec(accResWidth + 2, Bool()))
  for (i <- sqrtResidualAddend.indices) {
    sqrtResidualAddend(i) := (i match {
      case 0 =>
        isMag1
      case 1 =>
        isMag1
      case 2 =>
        isMag1 || isMag2
      case 3 =>
        (((!io.in.accRes(0) && isNeg) || (io.in.accResMinusUlp(0) && !isNeg)) && isMag1) || isMag2
      case x if (x == accResWidth + 1) =>
        (((true.B && isNeg) || (false.B && !isNeg)) && isMag1) ||
        (((!io.in.accRes(accResWidth - 3) && isNeg) || (io.in.accResMinusUlp(accResWidth - 3) && !isNeg)) && isMag2)
      case _ =>
        (((!io.in.accRes(i - 3) && isNeg) || (io.in.accResMinusUlp(i - 3) && !isNeg)) && isMag1) ||
        (((!io.in.accRes(i - 4) && isNeg) || (io.in.accResMinusUlp(i - 4) && !isNeg)) && isMag2)
    })
  }
  val alignedResidualAddend = Mux(
    io.in.sqrtOp,
    (sqrtResidualAddend.asUInt << Cat(io.remainingIterationsMinus2 + 1.U, false.B))(accResWidth + 1, 0),
    Cat(divResidualAddend.asUInt, 0.U((accResWidth + 1 - sigWidth).W))
  )

  var wallaceReducerInputColumns = Array.fill(accResWidth)(Seq.empty[Bool])
  val residualSumOffset = wallaceReducerInputColumns.size - (io.in.residualSum.getWidth - 2)
  val residualCarryOffset = wallaceReducerInputColumns.size - (io.in.residualCarry.getWidth - 2)
  val residualAddendOffset = wallaceReducerInputColumns.size - divResidualAddend.getWidth
  for (i <- wallaceReducerInputColumns.indices) {
    if (i >= residualSumOffset && (i - residualSumOffset) < io.in.residualSum.getWidth) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ io.in.residualSum(i - residualSumOffset)
    }
    if (i >= residualCarryOffset && (i - residualCarryOffset) < io.in.residualCarry.getWidth) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ io.in.residualCarry(i - residualCarryOffset)
    }
    if ((i + 2) < alignedResidualAddend.getWidth) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ alignedResidualAddend(i + 2)
    }
  }
  wallaceReducerInputColumns(residualAddendOffset) =
    wallaceReducerInputColumns(residualAddendOffset) :+ (!io.in.sqrtOp && isNeg)

  val wallaceReducerCounterUsage = CounterUsage()
  while (wallaceReducerInputColumns.map(_.size).max > 2) {
    wallaceReducerInputColumns = WallaceReducerCarrySave(wallaceReducerInputColumns, wallaceReducerCounterUsage)
  }

  val wallaceReducerOutputRow1 = Cat(wallaceReducerInputColumns.toSeq.reverse.map(_.lift(0).getOrElse(false.B)))
  val wallaceReducerOutputRow2 = Cat(wallaceReducerInputColumns.toSeq.reverse.map(_.lift(1).getOrElse(false.B)))

  val nextAccResUpper = Mux(
    decoder(
      minimizer = QMCMinimizer,
      input = Cat(isNeg, isMag2, isMag1),
      truthTable = TruthTable(
        table = Seq(
          BitPat("b110") -> BitPat("b1"),
          BitPat("b101") -> BitPat("b1"),
          BitPat("b000") -> BitPat("b1"),
          BitPat("b100") -> BitPat("b1"),
          BitPat("b001") -> BitPat("b0"),
          BitPat("b010") -> BitPat("b0")
        ),
        default = BitPat.dontCare(1)
      )
    ).asBool,
    io.in.accRes,
    io.in.accResMinusUlp
  )
  val nextAccResLower = decoder(
    minimizer = QMCMinimizer,
    input = Cat(isNeg, isMag2, isMag1),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b110") -> BitPat("b10"),
        BitPat("b101") -> BitPat("b01"),
        BitPat("b000") -> BitPat("b00"),
        BitPat("b100") -> BitPat("b00"),
        BitPat("b001") -> BitPat("b11"),
        BitPat("b010") -> BitPat("b10")
      ),
      default = BitPat.dontCare(2)
    )
  )
  val nextAccRes = Cat(nextAccResUpper, nextAccResLower)

  val nextAccResMinusUlpUpper = Mux(
    decoder(
      minimizer = QMCMinimizer,
      input = Cat(isNeg, isMag2, isMag1),
      truthTable = TruthTable(
        table = Seq(
          BitPat("b110") -> BitPat("b1"),
          BitPat("b101") -> BitPat("b1"),
          BitPat("b000") -> BitPat("b0"),
          BitPat("b100") -> BitPat("b0"),
          BitPat("b001") -> BitPat("b0"),
          BitPat("b010") -> BitPat("b0")
        ),
        default = BitPat.dontCare(1)
      )
    ).asBool,
    io.in.accRes(accResWidth - 3, 0),
    io.in.accResMinusUlp(accResWidth - 3, 0)
  )
  val nextAccResMinusUlpLower = decoder(
    minimizer = QMCMinimizer,
    input = Cat(isNeg, isMag2, isMag1),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b110") -> BitPat("b01"),
        BitPat("b101") -> BitPat("b00"),
        BitPat("b000") -> BitPat("b11"),
        BitPat("b100") -> BitPat("b11"),
        BitPat("b001") -> BitPat("b10"),
        BitPat("b010") -> BitPat("b01")
      ),
      default = BitPat.dontCare(2)
    )
  )
  val nextAccResMinusUlp = Cat(nextAccResMinusUlpUpper, nextAccResMinusUlpLower)

  val resultShamt = io.in.trialDivisor(resultShamtWidth - 1, 0)
  val nextResultShamt = resultShamt + 1.U(resultShamtWidth.W)

  val alignedNextAccRes = nextAccRes >> Cat(resultShamt, false.B)

  io.out.residualSum := Cat(wallaceReducerOutputRow1, alignedResidualAddend(1, 0))
  io.out.residualCarry := wallaceReducerOutputRow2(accResWidth - 1, accResWidth - sigWidth)
  io.out.provisionalResult := nextAccRes(accResWidth, 0)
  io.out.metadata := io.in.metadata

  io.feedbackData := io.in
  io.feedbackData.trialDivisor := Mux(
    io.in.sqrtOp,
    Cat(
      alignedNextAccRes(2, 0) ^ Replicate(3, !alignedNextAccRes(3)),
      0.U((sigWidth - 4 - resultShamtWidth).W),
      nextResultShamt
    ),
    io.in.trialDivisor
  )
  io.feedbackData.residualSum := Cat(wallaceReducerOutputRow1, 0.U((sigWidth + 3 - accResWidth).W))
  io.feedbackData.residualCarry := wallaceReducerOutputRow2(accResWidth - 1, accResWidth - sigWidth)
  io.feedbackData.accRes := nextAccRes(accResWidth - 1, 0)
  io.feedbackData.accResMinusUlp := nextAccResMinusUlp
}

class DivSqrtStage3(expWidth: Int, sigWidth: Int, accResWidth: Int) extends RawModule {
  override def desiredName: String = s"DivSqrtStage3_ew${expWidth}_sw${sigWidth}"

  val io = IO(new Bundle {
    val in = Input(new DivSqrtStage2ToStage3(expWidth, sigWidth, accResWidth))
    val out = Output(new DivSqrtStage3Output(expWidth, sigWidth))
  })

  val provisionalRemainder = Cat(
    io.in.residualSum(accResWidth + 1, accResWidth + 2 - sigWidth) + io.in.residualCarry,
    io.in.residualSum(accResWidth + 1 - sigWidth, 0)
  )

  val correctedResult = Mux(
    provisionalRemainder(accResWidth + 1),
    (io.in.provisionalResult - 1.U)(accResWidth, accResWidth - (sigWidth + 1)),
    io.in.provisionalResult(accResWidth, accResWidth - (sigWidth + 1))
  )

  io.out.outSig := Cat(correctedResult, provisionalRemainder.orR)
  io.out.metadata := io.in.metadata
}

class DivSqrtRecFN(expWidth: Int, sigWidth: Int) extends Module {
  val accResWidth = (sigWidth & ~1) + 2
  val iterationWidth = log2Ceil(sigWidth + 1) // '-1' to 'ceil((sigWidth + 1) >> 1) - 1'
  val resultShamtWidth = log2Ceil(sigWidth - 3) - 1

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreDivSqrtStageInput(expWidth, sigWidth)))
    val detectTininess = Input(Bool())
    val resp = Decoupled(new PostDivSqrtStageOutput(expWidth, sigWidth))
  })

  val buffer1 = Module(new SkidBuffer(new PreDivSqrtStageInput(expWidth, sigWidth)))
  val stage1 = Module(new DivSqrtStage1(expWidth, sigWidth, accResWidth, iterationWidth, resultShamtWidth))
  val buffer2 = Module(
    new IterativeSkidBuffer(new DivSqrtStage1ToStage2(expWidth, sigWidth, accResWidth), iterationWidth)
  )
  val stage2 = Module(new DivSqrtStage2(expWidth, sigWidth, accResWidth, iterationWidth, resultShamtWidth))
  val buffer3 = Module(new SkidBuffer(new DivSqrtStage2ToStage3(expWidth, sigWidth, accResWidth)))
  val stage3 = Module(new DivSqrtStage3(expWidth, sigWidth, accResWidth))
  val buffer4 = Module(new SkidBuffer(new PostDivSqrtStageOutput(expWidth, sigWidth)))

  buffer1.io.enq <> io.req

  val preStageIn = buffer1.io.deq.bits

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, preStageIn.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, preStageIn.b)

  val specialCaseA = rawA.isNaN || rawA.isInf || rawA.isZero
  val specialCaseB = rawB.isNaN || rawB.isInf || rawB.isZero
  val normalCase_div = !specialCaseA && !specialCaseB
  val normalCase_sqrt = !specialCaseA && !rawA.sign
  val normalCase = Mux(
    preStageIn.sqrtOp,
    normalCase_sqrt,
    normalCase_div
  )

  val notSigNaNIn_invalidExc_div = (rawA.isZero && rawB.isZero) || (rawA.isInf && rawB.isInf)
  val notSigNaNIn_invalidExc_sqrt = !rawA.isNaN && !rawA.isZero && rawA.sign
  val majorExc_pre = Mux(
    preStageIn.sqrtOp,
    IsSigNaNRawFloat(rawA) || notSigNaNIn_invalidExc_sqrt,
    IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || notSigNaNIn_invalidExc_div ||
      (!rawA.isNaN && !rawA.isInf && rawB.isZero)
  )
  val isNaNOut_pre = Mux(
    preStageIn.sqrtOp,
    rawA.isNaN || notSigNaNIn_invalidExc_sqrt,
    rawA.isNaN || rawB.isNaN || notSigNaNIn_invalidExc_div
  )
  val notNaN_isInfOut_pre = Mux(
    preStageIn.sqrtOp,
    rawA.isInf,
    rawA.isInf || rawB.isZero
  )
  val notNaN_isZeroOut_pre = Mux(
    preStageIn.sqrtOp,
    rawA.isZero,
    rawA.isZero || rawB.isInf
  )
  val notNaN_signOut_pre = rawA.sign ^ (!preStageIn.sqrtOp && rawB.sign)

  val negIn2SExpPlusOffset = Cat(rawB.sExp(expWidth), ~rawB.sExp(expWidth - 1, 0)).asSInt
  val sExpQuot_div = rawA.sExp(expWidth, 0).pad(expWidth + 3).asSInt + negIn2SExpPlusOffset
  val common_sExpOut_pre = Mux(
    preStageIn.sqrtOp,
    (rawA.sExp(expWidth, 1) +& (BigInt(1) << (expWidth - 1)).U).pad(expWidth + 2).asSInt,
    Cat(
      Mux(
        (BigInt(7) << (expWidth - 2)).S <= sExpQuot_div,
        6.U(4.W),
        sExpQuot_div(expWidth + 1, expWidth - 2)
      ),
      sExpQuot_div(expWidth - 3, 0)
    ).asSInt
  )

  val roundingMode_pre = preStageIn.roundingMode

  val metadata = Cat(
    majorExc_pre,
    isNaNOut_pre,
    notNaN_isInfOut_pre,
    notNaN_isZeroOut_pre,
    notNaN_signOut_pre,
    common_sExpOut_pre,
    roundingMode_pre
  )

  stage1.io.in.normalCase := normalCase
  stage1.io.in.sqrtOp := preStageIn.sqrtOp
  stage1.io.in.isSExpOdd_sqrt := rawA.sExp(0)
  stage1.io.in.in1Sig := rawA.sig(sigWidth - 2, 0)
  stage1.io.in.in2Sig := rawB.sig(sigWidth - 2, 0)
  stage1.io.in.metadata := metadata
  buffer2.io.enq.bits := stage1.io.out
  buffer2.io.enq.valid := buffer1.io.deq.valid
  buffer1.io.deq.ready := buffer2.io.enq.ready
  buffer2.io.feedbackData := stage2.io.feedbackData
  buffer2.io.totalIterationsMinus2 := stage1.io.totalIterationsMinus2

  stage2.io.in := buffer2.io.deq.bits
  stage2.io.remainingIterationsMinus2 := buffer2.io.remainingIterationsMinus2
  buffer3.io.enq.bits := stage2.io.out
  buffer3.io.enq.valid := buffer2.io.deq.valid
  buffer2.io.deq.ready := buffer3.io.enq.ready

  stage3.io.in := buffer3.io.deq.bits
  val postStageIn = stage3.io.out

  val majorExc_post = postStageIn.metadata(expWidth + 9)
  val isNaNOut_post = postStageIn.metadata(expWidth + 8)
  val notNaN_isInfOut_post = postStageIn.metadata(expWidth + 7)
  val notNaN_isZeroOut_post = postStageIn.metadata(expWidth + 6)
  val notNaN_signOut_post = postStageIn.metadata(expWidth + 5)
  val common_sExpOut_post = postStageIn.metadata(expWidth + 4, 3).asSInt
  val common_sigOut_post = postStageIn.outSig
  val roundingMode_post = postStageIn.metadata(2, 0)

  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
  roundRawFNToRecFN.io.invalidExc := majorExc_post && isNaNOut_post
  roundRawFNToRecFN.io.infiniteExc := majorExc_post && !isNaNOut_post
  roundRawFNToRecFN.io.in.isNaN := isNaNOut_post
  roundRawFNToRecFN.io.in.isInf := notNaN_isInfOut_post
  roundRawFNToRecFN.io.in.isZero := notNaN_isZeroOut_post
  roundRawFNToRecFN.io.in.sign := notNaN_signOut_post
  roundRawFNToRecFN.io.in.sExp := common_sExpOut_post
  roundRawFNToRecFN.io.in.sig := common_sigOut_post
  roundRawFNToRecFN.io.roundingMode := roundingMode_post
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  buffer4.io.enq.bits.out := roundRawFNToRecFN.io.out
  buffer4.io.enq.bits.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
  buffer4.io.enq.valid := buffer3.io.deq.valid
  buffer3.io.deq.ready := buffer4.io.enq.ready

  io.resp <> buffer4.io.deq
}
