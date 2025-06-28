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
  val roundMetadata = UInt((expWidth + 10).W)
}

class DivSqrtStage1ToStage2(expWidth: Int, sigWidth: Int, accResWidth: Int) extends Bundle {
  val sqrtOp = Bool()
  val trialDivisor = UInt((sigWidth - 1).W)
  val residualSum = UInt((sigWidth + 3).W)
  val residualCarry = UInt(sigWidth.W)
  val accRes = UInt(accResWidth.W)
  val accResMinusUlp = UInt(accResWidth.W)
  val roundMetadata = UInt((expWidth + 10).W)
}

class DivSqrtStage2ToStage3(expWidth: Int, sigWidth: Int, accResWidth: Int) extends Bundle {
  val provisionalRemainder = UInt((accResWidth + 2).W)
  val provisionalResult = UInt((accResWidth + 1).W)
  val roundMetadata = UInt((expWidth + 10).W)
}

class DivSqrtStage3Output(expWidth: Int, sigWidth: Int) extends Bundle {
  val outSig = UInt((sigWidth + 3).W)
  val roundMetadata = UInt((expWidth + 10).W)
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
  val resultDigitEncodingPos2 = BitPat("b10")
  val resultDigitEncodingPos1 = BitPat("b01")
  val resultDigitEncodingZero = BitPat("b00")
  val resultDigitEncodingNeg1 = BitPat("b01")
  val resultDigitEncodingNeg2 = BitPat("b10")

  val resultDigitSelectionRangesJ2: Seq[ResultDigitSelectionRangeConfig] = Seq(
    ResultDigitSelectionRangeConfig((12, 24), (4, 11), (-4, 3), (-13, -5), (-25, -14)),
    ResultDigitSelectionRangeConfig((14, 26), (4, 13), (-4, 3), (-14, -5), (-28, -15)),
    ResultDigitSelectionRangeConfig((16, 29), (4, 15), (-6, 3), (-16, -7), (-30, -17)),
    ResultDigitSelectionRangeConfig((16, 32), (4, 15), (-6, 3), (-17, -7), (-33, -18)),
    ResultDigitSelectionRangeConfig((18, 34), (6, 17), (-6, 5), (-18, -7), (-36, -19)),
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
    resultDigitSelectionRangesJ2.zipWithIndex.flatMap { case (rangeConfig, truncTrialDiv) =>
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
      default = BitPat.dontCare(2)
    )
  )

  io.isNeg := !estimatedResidual(7)
  io.isMag2 := resultDigitEncoding(1)
  io.isMag1 := resultDigitEncoding(0)
}

class PreDivSqrtStage(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new PreDivSqrtStageInput(expWidth, sigWidth))
    val out = Output(new DivSqrtStage1Input(expWidth, sigWidth))
  })

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.in.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.in.b)

  val specialCaseA = rawA.isNaN || rawA.isInf || rawA.isZero
  val specialCaseB = rawB.isNaN || rawB.isInf || rawB.isZero

  val notSigNaN_invalidExc_div = (rawA.isZero && rawB.isZero) || (rawA.isInf && rawB.isInf)
  val notSigNaN_invalidExc_sqrt = !rawA.isNaN && !rawA.isZero && rawA.sign
  val majorExc = Mux(
    io.in.sqrtOp,
    IsSigNaNRawFloat(rawA) || notSigNaN_invalidExc_sqrt,
    IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || notSigNaN_invalidExc_div ||
      (!rawA.isNaN && !rawA.isInf && rawB.isZero)
  )
  val isNaNOut = Mux(
    io.in.sqrtOp,
    rawA.isNaN || notSigNaN_invalidExc_sqrt,
    rawA.isNaN || rawB.isNaN || notSigNaN_invalidExc_div
  )
  val notNaN_isInfOut = Mux(
    io.in.sqrtOp,
    rawA.isInf,
    rawA.isInf || rawB.isZero
  )
  val notNaN_isZeroOut = Mux(
    io.in.sqrtOp,
    rawA.isZero,
    rawA.isZero || rawB.isInf
  )
  val notNaN_signOut = rawA.sign ^ (!io.in.sqrtOp && rawB.sign)

  val negIn2SExpPlusOffset = Cat(rawB.sExp(expWidth), ~rawB.sExp(expWidth - 1, 0)).asSInt
  val sExpQuot_div = rawA.sExp(expWidth, 0).pad(expWidth + 3).asSInt + negIn2SExpPlusOffset
  val common_sExpOut = Mux(
    io.in.sqrtOp,
    (rawA.sExp(expWidth, 1) +& (BigInt(1) << (expWidth - 1)).U).pad(expWidth + 2).asSInt,
    Cat(
      Mux(
        sExpQuot_div >= (BigInt(7) << (expWidth - 2)).S,
        6.U(4.W),
        sExpQuot_div(expWidth + 1, expWidth - 2)
      ),
      sExpQuot_div(expWidth - 3, 0)
    ).asSInt
  )

  io.out.normalCase := Mux(
    io.in.sqrtOp,
    !specialCaseA && !rawA.sign,
    !specialCaseA && !specialCaseB
  )
  io.out.sqrtOp := io.in.sqrtOp
  io.out.isSExpOdd_sqrt := rawA.sExp(0)
  io.out.in1Sig := rawA.sig(sigWidth - 2, 0)
  io.out.in2Sig := rawB.sig(sigWidth - 2, 0)
  io.out.roundMetadata := Cat(
    majorExc,
    isNaNOut,
    notNaN_isInfOut,
    notNaN_isZeroOut,
    notNaN_signOut,
    common_sExpOut,
    io.in.roundingMode
  )
}

class DivSqrtStage1(expWidth: Int, sigWidth: Int, accResWidth: Int, iterationWidth: Int, resultShamtWidth: Int)
    extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new DivSqrtStage1Input(expWidth, sigWidth))
    val out = Output(new DivSqrtStage1ToStage2(expWidth, sigWidth, accResWidth))
    val totalIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

  val initialResidualSum_div = Cat("b0001".U(4.W), io.in.in1Sig)

  val firstAccRes_div = decoder(
    minimizer = QMCMinimizer,
    input = Cat(io.in.in2Sig(sigWidth - 2, sigWidth - 4), initialResidualSum_div(sigWidth - 2, sigWidth - 3)),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b1????") -> BitPat("b01"),
        BitPat("b01???") -> BitPat("b01"),
        BitPat("b00111") -> BitPat("b10"),
        BitPat("b00110") -> BitPat("b01"),
        BitPat("b0010?") -> BitPat("b01"),
        BitPat("b0001?") -> BitPat("b10"),
        BitPat("b0000?") -> BitPat("b01")
      ),
      default = BitPat.dontCare(2)
    )
  )
  val firstAccResMinusUlp_div = decoder(
    minimizer = QMCMinimizer,
    input = Cat(io.in.in2Sig(sigWidth - 2, sigWidth - 4), initialResidualSum_div(sigWidth - 2, sigWidth - 3)),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b1????") -> BitPat("b00"),
        BitPat("b01???") -> BitPat("b00"),
        BitPat("b00111") -> BitPat("b01"),
        BitPat("b00110") -> BitPat("b00"),
        BitPat("b0010?") -> BitPat("b00"),
        BitPat("b0001?") -> BitPat("b01"),
        BitPat("b0000?") -> BitPat("b00")
      ),
      default = BitPat.dontCare(2)
    )
  )

  val isMag2_div = firstAccRes_div(1)
  val isMag1_div = firstAccRes_div(0)

  val residualAddend_div = Wire(Vec(sigWidth + 1, Bool()))
  for (i <- residualAddend_div.indices) {
    residualAddend_div(i) := (i match {
      case 0 =>
        ((io.in.in2Sig(0) && isMag1_div) || (false.B && isMag2_div)) ^ true.B
      case x if (x == sigWidth - 1) =>
        ((true.B && isMag1_div) || (io.in.in2Sig(sigWidth - 2) && isMag2_div)) ^ true.B
      case x if (x == sigWidth) =>
        ((false.B && isMag1_div) || (true.B && isMag2_div)) ^ true.B
      case _ =>
        ((io.in.in2Sig(i) && isMag1_div) || (io.in.in2Sig(i - 1) && isMag2_div)) ^ true.B
    })
  }

  var wallaceReducerInputColumns = Array.fill(sigWidth + 1)(Seq.empty[Bool])
  for (i <- wallaceReducerInputColumns.indices) {
    if (i < initialResidualSum_div.getWidth) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ initialResidualSum_div(i)
    }
    if (i < residualAddend_div.getWidth) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ residualAddend_div(i)
    }
  }
  wallaceReducerInputColumns(0) = wallaceReducerInputColumns(0) :+ true.B

  val wallaceReducerCounterUsage = CounterUsage()
  while (wallaceReducerInputColumns.map(_.size).max > 2) {
    wallaceReducerInputColumns = WallaceReducerCarrySave(wallaceReducerInputColumns, wallaceReducerCounterUsage)
  }

  val wallaceReducerOutputRow1 = Cat(wallaceReducerInputColumns.toSeq.reverse.map(_.lift(0).getOrElse(false.B)))
  val wallaceReducerOutputRow2 = Cat(wallaceReducerInputColumns.toSeq.reverse.map(_.lift(1).getOrElse(false.B)))

  val initialResidualSum_sqrt = Mux(
    io.in.isSExpOdd_sqrt,
    Cat("b111".U(3.W), io.in.in1Sig, false.B),
    Cat("b1101".U(4.W), io.in.in1Sig)
  )

  val secondAccRes_sqrt = decoder(
    minimizer = QMCMinimizer,
    input = initialResidualSum_sqrt(sigWidth, sigWidth - 5),
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
  val secondAccResMinusUlp_sqrt = decoder(
    minimizer = QMCMinimizer,
    input = initialResidualSum_sqrt(sigWidth, sigWidth - 5),
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

  val secondResidualSum_sqrt = initialResidualSum_sqrt(sigWidth - 2, 0)
  val secondResidualCarry_sqrt = decoder(
    minimizer = QMCMinimizer,
    input = initialResidualSum_sqrt(sigWidth, sigWidth - 5),
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
      secondAccRes_sqrt(2, 0) ^ Replicate(3, !secondAccRes_sqrt(3)),
      0.U((sigWidth - 4 - resultShamtWidth).W),
      1.U(resultShamtWidth.W)
    ),
    io.in.in2Sig
  )
  io.out.residualSum := Mux(
    io.in.sqrtOp,
    Cat(secondResidualSum_sqrt, 0.U(4.W)),
    Cat(wallaceReducerOutputRow1, 0.U(2.W))
  )
  io.out.residualCarry := Mux(
    io.in.sqrtOp,
    Cat(secondResidualCarry_sqrt, 0.U((sigWidth - 6).W)),
    wallaceReducerOutputRow2(sigWidth, 1)
  )
  io.out.accRes := Mux(
    io.in.sqrtOp,
    Cat(0.U((accResWidth - 5).W), secondAccRes_sqrt),
    Cat(0.U((accResWidth - 2).W), firstAccRes_div)
  )
  io.out.accResMinusUlp := Mux(
    io.in.sqrtOp,
    Cat(0.U((accResWidth - 5).W), secondAccResMinusUlp_sqrt),
    Cat(0.U((accResWidth - 2).W), firstAccResMinusUlp_div)
  )
  io.out.roundMetadata := io.in.roundMetadata

  io.totalIterationsMinus2 := Mux(
    io.in.normalCase,
    Mux(
      io.in.sqrtOp,
      ((sigWidth >> 1) - 3).U(iterationWidth.W),
      ((sigWidth >> 1) - 1).U(iterationWidth.W)
    ),
    (-1.S(iterationWidth.W)).asUInt
  )
}

class DivSqrtStage2(expWidth: Int, sigWidth: Int, accResWidth: Int, iterationWidth: Int, resultShamtWidth: Int)
    extends RawModule {
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

  val residualAddend_div = Wire(Vec(sigWidth + 1, Bool()))
  for (i <- residualAddend_div.indices) {
    residualAddend_div(i) := (i match {
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
  val residualAddend_sqrt = Wire(Vec(accResWidth + 2, Bool()))
  for (i <- residualAddend_sqrt.indices) {
    residualAddend_sqrt(i) := (i match {
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
    (residualAddend_sqrt.asUInt << Cat(io.remainingIterationsMinus2 + 1.U, false.B))(accResWidth + 1, 0),
    Cat(residualAddend_div.asUInt, 0.U((accResWidth + 1 - sigWidth).W))
  )

  var wallaceReducerInputColumns = Array.fill(accResWidth)(Seq.empty[Bool])
  val residualSumOffset = wallaceReducerInputColumns.size - (io.in.residualSum.getWidth - 2)
  val residualCarryOffset = wallaceReducerInputColumns.size - (io.in.residualCarry.getWidth - 2)
  val residualAddendOffset = wallaceReducerInputColumns.size - residualAddend_div.getWidth
  for (i <- wallaceReducerInputColumns.indices) {
    if ((i >= residualSumOffset) && ((i - residualSumOffset) < io.in.residualSum.getWidth)) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ io.in.residualSum(i - residualSumOffset)
    }
    if ((i >= residualCarryOffset) && ((i - residualCarryOffset) < io.in.residualCarry.getWidth)) {
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

  io.out.provisionalRemainder := Cat(FinalAdder(wallaceReducerInputColumns), alignedResidualAddend(1, 0))
  io.out.provisionalResult := nextAccRes(accResWidth, 0)
  io.out.roundMetadata := io.in.roundMetadata

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
  val io = IO(new Bundle {
    val in = Input(new DivSqrtStage2ToStage3(expWidth, sigWidth, accResWidth))
    val out = Output(new DivSqrtStage3Output(expWidth, sigWidth))
  })

  val correctedResult = Mux(
    io.in.provisionalRemainder(accResWidth + 1),
    (io.in.provisionalResult - 1.U)(accResWidth, accResWidth - (sigWidth + 1)),
    io.in.provisionalResult(accResWidth, accResWidth - (sigWidth + 1))
  )

  io.out.outSig := Cat(correctedResult, io.in.provisionalRemainder.orR)
  io.out.roundMetadata := io.in.roundMetadata
}

class DivSqrtRecFN(expWidth: Int, sigWidth: Int) extends Module {
  require(sigWidth >= 6)

  val accResWidth = (sigWidth & ~1) + 2
  val iterationWidth = log2Ceil(sigWidth - 1) // '-1' to 'ceil((sigWidth - 1) >> 1) - 1'
  val resultShamtWidth = log2Ceil(sigWidth - 3) - 1

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreDivSqrtStageInput(expWidth, sigWidth)))
    val detectTininess = Input(Bool())
    val resp = Decoupled(new PostDivSqrtStageOutput(expWidth, sigWidth))
  })

  val preStage = Module(new PreDivSqrtStage(expWidth, sigWidth))
  val stage1 = Module(new DivSqrtStage1(expWidth, sigWidth, accResWidth, iterationWidth, resultShamtWidth))
  val buffer1 = Module(
    new IterativeSkidBuffer(new DivSqrtStage1ToStage2(expWidth, sigWidth, accResWidth), iterationWidth)
  )
  val stage2 = Module(new DivSqrtStage2(expWidth, sigWidth, accResWidth, iterationWidth, resultShamtWidth))
  val buffer2 = Module(new SkidBuffer(new DivSqrtStage2ToStage3(expWidth, sigWidth, accResWidth)))
  val stage3 = Module(new DivSqrtStage3(expWidth, sigWidth, accResWidth))
  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))

  preStage.io.in := io.req.bits
  stage1.io.in := preStage.io.out
  buffer1.io.enq.bits := stage1.io.out
  buffer1.io.enq.valid := io.req.valid
  io.req.ready := buffer1.io.enq.ready
  buffer1.io.feedbackData := stage2.io.feedbackData
  buffer1.io.totalIterationsMinus2 := stage1.io.totalIterationsMinus2

  stage2.io.in := buffer1.io.deq.bits
  stage2.io.remainingIterationsMinus2 := buffer1.io.remainingIterationsMinus2
  buffer2.io.enq.bits := stage2.io.out
  buffer2.io.enq.valid := buffer1.io.deq.valid
  buffer1.io.deq.ready := buffer2.io.enq.ready

  stage3.io.in := buffer2.io.deq.bits

  val majorExc = stage3.io.out.roundMetadata(expWidth + 9)
  val isNaNOut = stage3.io.out.roundMetadata(expWidth + 8)

  roundRawFNToRecFN.io.invalidExc := majorExc && isNaNOut
  roundRawFNToRecFN.io.infiniteExc := majorExc && !isNaNOut
  roundRawFNToRecFN.io.in.isNaN := isNaNOut
  roundRawFNToRecFN.io.in.isInf := stage3.io.out.roundMetadata(expWidth + 7)
  roundRawFNToRecFN.io.in.isZero := stage3.io.out.roundMetadata(expWidth + 6)
  roundRawFNToRecFN.io.in.sign := stage3.io.out.roundMetadata(expWidth + 5)
  roundRawFNToRecFN.io.in.sExp := stage3.io.out.roundMetadata(expWidth + 4, 3).asSInt
  roundRawFNToRecFN.io.in.sig := stage3.io.out.outSig
  roundRawFNToRecFN.io.roundingMode := stage3.io.out.roundMetadata(2, 0)
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.resp.bits.out := roundRawFNToRecFN.io.out
  io.resp.bits.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
  io.resp.valid := buffer2.io.deq.valid
  buffer2.io.deq.ready := io.resp.ready
}
