package HardInt

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import freechips.rocketchip.rocket.DecodeLogic
import freechips.rocketchip.rocket.constants.ScalarOpConstants
import HardUtils._
import HardInt.ALU._

class PreDividerStageInput(dataWidth: Int, tagWidth: Int) extends Bundle with ScalarOpConstants {
  val fn = UInt(SZ_ALU_FN.W)
  val dw = UInt(SZ_DW.W)
  val in1 = UInt(dataWidth.W)
  val in2 = UInt(dataWidth.W)
  val tag = UInt(tagWidth.W)
}

class DividerStage1Input(dataWidth: Int) extends Bundle {
  val dividendSign = Bool()
  val divisorSign = Bool()
  val dividend = UInt(dataWidth.W)
  val divisor = UInt(dataWidth.W)
}

class DividerStage1ToStage2(dataWidth: Int, clzWidth: Int) extends Bundle {
  val dividend = UInt(dataWidth.W)
  val divisor = UInt(dataWidth.W)
  val dividendClz = UInt(clzWidth.W)
  val divisorClz = UInt(clzWidth.W)
}

class DividerStage2ToStage3(dataWidth: Int, remainderShamtWidth: Int) extends Bundle {
  val dividendSign = Bool()
  val isDivisorZero = Bool()
  val isDivisorNeg1 = Bool()
  val isDivisorPos1 = Bool()
  val normalizedDivisor = UInt(dataWidth.W)
  val residualSum = UInt((dataWidth + 4).W)
  val residualCarry = UInt(dataWidth.W)
  val accumulatedQuotient = UInt((dataWidth - 2).W)
  val accumulatedQuotientMinusUlp = UInt((dataWidth - 2).W)
  val remainderShamt = UInt(remainderShamtWidth.W)
}

class DividerStage3ToStage4(dataWidth: Int, remainderShamtWidth: Int) extends Bundle {
  val dividendSign = Bool()
  val normalizedDivisor = UInt(dataWidth.W)
  val residualSum = UInt((dataWidth + 1).W)
  val residualCarry = UInt(dataWidth.W)
  val provisionalQuotient = UInt(dataWidth.W)
  val remainderShamt = UInt(remainderShamtWidth.W)
}

class DividerStage4Output(dataWidth: Int) extends Bundle {
  val quotient = UInt(dataWidth.W)
  val remainder = UInt(dataWidth.W)
}

class PostDividerStageOutput(dataWidth: Int, tagWidth: Int) extends Bundle {
  val out = UInt(dataWidth.W)
  val tag = UInt(tagWidth.W)
}

class DividerMetadata(tagWidth: Int) extends Bundle {
  val isRemainder = Bool()
  val isHalfWidth = Bool()
  val tag = UInt(tagWidth.W)
}

case class QuotientDigitSelectionRangeConfig(
  pos2Range: (Int, Int),
  pos1Range: (Int, Int),
  zeroRange: (Int, Int),
  neg1Range: (Int, Int),
  neg2Range: (Int, Int)
)

object QuotientDigitSelector {
  val quotientDigitEncodingPos2 = BitPat("b10")
  val quotientDigitEncodingPos1 = BitPat("b01")
  val quotientDigitEncodingZero = BitPat("b00")
  val quotientDigitEncodingNeg1 = BitPat("b01")
  val quotientDigitEncodingNeg2 = BitPat("b10")

  val quotientDigitSelectionRanges: Seq[QuotientDigitSelectionRangeConfig] = Seq(
    QuotientDigitSelectionRangeConfig((-44, -25), (-24, -9), (-8, 7), (8, 23), (24, 42)),
    QuotientDigitSelectionRangeConfig((-41, -23), (-22, -9), (-8, 7), (8, 19), (20, 40)),
    QuotientDigitSelectionRangeConfig((-38, -21), (-20, -9), (-8, 7), (8, 19), (20, 37)),
    QuotientDigitSelectionRangeConfig((-36, -21), (-20, -9), (-8, 5), (6, 17), (18, 34)),
    QuotientDigitSelectionRangeConfig((-33, -19), (-18, -7), (-6, 3), (4, 15), (16, 32)),
    QuotientDigitSelectionRangeConfig((-30, -17), (-16, -7), (-6, 3), (4, 15), (16, 29)),
    QuotientDigitSelectionRangeConfig((-28, -15), (-14, -5), (-4, 3), (4, 13), (14, 26)),
    QuotientDigitSelectionRangeConfig((-25, -14), (-13, -5), (-4, 3), (4, 11), (12, 24))
  )

  private def encodeLutInput(truncNormDiv: Int, estResid: Int): BitPat = {
    val lutInput = (truncNormDiv << 7) | (estResid & 0x7f)
    BitPat(lutInput.U(10.W))
  }

  private def buildLutEntries(
    truncNormDiv: Int,
    rangeConfig:  QuotientDigitSelectionRangeConfig
  ): Seq[(BitPat, BitPat)] = {
    val rangeMappings = Seq(
      rangeConfig.pos2Range -> quotientDigitEncodingPos2,
      rangeConfig.pos1Range -> quotientDigitEncodingPos1,
      rangeConfig.zeroRange -> quotientDigitEncodingZero,
      rangeConfig.neg1Range -> quotientDigitEncodingNeg1,
      rangeConfig.neg2Range -> quotientDigitEncodingNeg2
    )

    rangeMappings.flatMap { case ((min, max), quotientDigitEncoding) =>
      (min to max).map { estResid =>
        encodeLutInput(truncNormDiv, estResid) -> quotientDigitEncoding
      }
    }
  }

  lazy val lutEntries: Map[BitPat, BitPat] = {
    quotientDigitSelectionRanges.zipWithIndex.flatMap { case (rangeConfig, truncNormDiv) =>
      buildLutEntries(truncNormDiv, rangeConfig)
    }.toMap
  }
}

class QuotientDigitSelector extends RawModule {
  val io = IO(new Bundle {
    val truncatedNormalizedDivisor = Input(UInt(4.W))
    val truncatedResidualSum = Input(UInt(8.W))
    val truncatedResidualCarry = Input(UInt(8.W))
    val isNeg = Output(Bool())
    val isMag2 = Output(Bool())
    val isMag1 = Output(Bool())
  })

  val invertedDivisorSign = io.truncatedNormalizedDivisor(3)

  val divisorIndex = io.truncatedNormalizedDivisor(2, 0) ^ Replicate(3, invertedDivisorSign)

  val estimatedResidual = io.truncatedResidualSum + io.truncatedResidualCarry

  val quotientDigitEncoding = decoder(
    minimizer = EspressoMinimizer,
    input = Cat(divisorIndex, estimatedResidual(7, 1)),
    truthTable = TruthTable(
      table = QuotientDigitSelector.lutEntries,
      default = BitPat.dontCare(2)
    )
  )

  io.isNeg := estimatedResidual(7) ^ invertedDivisorSign
  io.isMag2 := quotientDigitEncoding(1)
  io.isMag1 := quotientDigitEncoding(0)
}

class PreDividerStage(dataWidth: Int, tagWidth: Int) extends RawModule with ScalarOpConstants {
  val io = IO(new Bundle {
    val in = Input(new PreDividerStageInput(dataWidth, tagWidth))
    val out = Output(new Bundle {
      val data = new DividerStage1Input(dataWidth)
      val metadata = new DividerMetadata(tagWidth)
    })
  })

  val halfWidth = dataWidth >> 1

  val decodeTable = List(
    FN_DIV -> List(N, Y),
    FN_DIVU -> List(N, N),
    FN_REM -> List(Y, Y),
    FN_REMU -> List(Y, N)
  )
  val isRemainder :: isSigned :: Nil =
    DecodeLogic(io.in.fn, List(X, X), decodeTable).map(_.asBool)

  val isHalfWidth = (dataWidth > 32).B && (io.in.dw === DW_32)

  val dividendSign = isSigned && Mux(
    isHalfWidth,
    io.in.in1(halfWidth - 1),
    io.in.in1(dataWidth - 1)
  )
  val divisorSign = isSigned && Mux(
    isHalfWidth,
    io.in.in2(halfWidth - 1),
    io.in.in2(dataWidth - 1)
  )

  val dividend = Cat(
    Mux(
      isHalfWidth,
      Replicate(halfWidth, dividendSign),
      io.in.in1(dataWidth - 1, halfWidth)
    ),
    io.in.in1(halfWidth - 1, 0)
  )
  val divisor = Cat(
    Mux(
      isHalfWidth,
      Replicate(halfWidth, divisorSign),
      io.in.in2(dataWidth - 1, halfWidth)
    ),
    io.in.in2(halfWidth - 1, 0)
  )

  io.out.data.dividendSign := dividendSign
  io.out.data.divisorSign := divisorSign
  io.out.data.dividend := dividend
  io.out.data.divisor := divisor
  io.out.metadata.isRemainder := isRemainder
  io.out.metadata.isHalfWidth := isHalfWidth
  io.out.metadata.tag := io.in.tag
}

class DividerStage1(dataWidth: Int, clzWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new DividerStage1Input(dataWidth))
    val out = Output(new DividerStage1ToStage2(dataWidth, clzWidth))
  })

  val dividendClz = CountLeadingZeros(io.in.dividend ^ Replicate(dataWidth, io.in.dividendSign))
  val divisorClz = CountLeadingZeros(io.in.divisor ^ Replicate(dataWidth, io.in.divisorSign))

  io.out.dividend := io.in.dividend
  io.out.divisor := io.in.divisor
  io.out.dividendClz := dividendClz
  io.out.divisorClz := divisorClz
}

class DividerStage2(dataWidth: Int, clzWidth: Int, clzDiffWidth: Int, remainderShamtWidth: Int, iterationWidth: Int)
    extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new DividerStage1ToStage2(dataWidth, clzWidth))
    val out = Output(new DividerStage2ToStage3(dataWidth, remainderShamtWidth))
    val totalIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

  val normalizedDividend = (io.in.dividend << io.in.dividendClz)(dataWidth, 0)
  val normalizedDivisor = (io.in.divisor << io.in.divisorClz)(dataWidth, 0)

  val dividendSign = normalizedDividend(dataWidth)
  val divisorSign = normalizedDivisor(dataWidth)

  val isDivisorZero = if (isPow2(dataWidth)) {
    !io.in.divisor(0) && io.in.divisorClz(clzWidth - 1)
  } else {
    !io.in.divisor(0) && (io.in.divisorClz === dataWidth.U(clzWidth.W))
  }
  val isDivisorNeg1 = if (isPow2(dataWidth)) {
    io.in.divisor(0) && io.in.divisorClz(clzWidth - 1)
  } else {
    io.in.divisor(0) && (io.in.divisorClz === dataWidth.U(clzWidth.W))
  }
  val isDivisorPos1 = if (isPow2(dataWidth)) {
    io.in.divisor(0) && io.in.divisorClz(clzWidth - 2, 0).andR
  } else {
    io.in.divisor(0) && (io.in.divisorClz === (dataWidth - 1).U(clzWidth.W))
  }

  val clzDiff = io.in.divisorClz.pad(clzDiffWidth) - io.in.dividendClz.pad(clzDiffWidth)

  // Detect edge case: Dividend = -2^i, Divisor = +2^j where i == j (clzDiff = -1).
  // This results in quotient = -1, remainder = 0.
  val isNegPow2DividedByMatchingPosPow2 =
    dividendSign && (normalizedDividend(dataWidth - 2, 1) === 0.U((dataWidth - 2).W)) &&
      !divisorSign && (normalizedDivisor(dataWidth - 2, 1) === 0.U((dataWidth - 2).W)) &&
      clzDiff(clzDiffWidth - 2, 0).andR

  val initialResidualSum = Mux(
    isDivisorZero || isDivisorNeg1 || isDivisorPos1 ||
      (clzDiff(clzDiffWidth - 1) && !isNegPow2DividedByMatchingPosPow2),
    // Special case or negative clzDiff: [s].[s][s][originalDividend][0]
    Cat(Replicate(3, dividendSign), io.in.dividend, false.B),
    Mux(
      clzDiff(0),
      // Odd clzDiff or edge case:       [s].[s][s][s][normalizedDividend]
      Cat(Replicate(4, dividendSign), normalizedDividend(dataWidth - 1, 0)),
      // Even clzDiff:                   [s].[s][s][normalizedDividend][0]
      Cat(Replicate(3, dividendSign), normalizedDividend(dataWidth - 1, 0), false.B)
    )
  )
  val initialResidualCarry = 0.U(dataWidth.W) // Use dataWidth as width since 4 LSBs are always 0

  val initialAccumulatedQuotient = 0.U((dataWidth - 2).W)
  val initialAccumulatedQuotientMinusUlp = (-1.S((dataWidth - 2).W)).asUInt

  val remainderShamt = Mux(
    isDivisorZero || isDivisorNeg1 || isDivisorPos1 ||
      (clzDiff(clzDiffWidth - 1) && !isNegPow2DividedByMatchingPosPow2),
    // Special case or negative clzDiff: No shift needed
    0.U(remainderShamtWidth.W),
    // General case or edge case:        Shift by divisorClz
    io.in.divisorClz(remainderShamtWidth - 1, 0)
  )

  val totalIterationsMinus2 = Mux(
    isDivisorZero || isDivisorNeg1 || isDivisorPos1 ||
      (clzDiff(clzDiffWidth - 1) && !isNegPow2DividedByMatchingPosPow2),
    // Special case or negative clzDiff: 0 or 1 iteration
    (-1.S(iterationWidth.W)).asUInt,
    // General case or edge case:        ceil(clzDiff >> 1) + 1 iterations
    (clzDiff(iterationWidth, 0) - 1.U((iterationWidth + 1).W))(iterationWidth, 1)
  )

  io.out.dividendSign := dividendSign
  io.out.isDivisorZero := isDivisorZero
  io.out.isDivisorNeg1 := isDivisorNeg1
  io.out.isDivisorPos1 := isDivisorPos1
  io.out.normalizedDivisor := normalizedDivisor(dataWidth - 1, 0)
  io.out.residualSum := initialResidualSum
  io.out.residualCarry := initialResidualCarry
  io.out.accumulatedQuotient := initialAccumulatedQuotient
  io.out.accumulatedQuotientMinusUlp := initialAccumulatedQuotientMinusUlp
  io.out.remainderShamt := remainderShamt

  io.totalIterationsMinus2 := totalIterationsMinus2
}

class DividerStage3(dataWidth: Int, remainderShamtWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new DividerStage2ToStage3(dataWidth, remainderShamtWidth))
    val out = Output(new DividerStage3ToStage4(dataWidth, remainderShamtWidth))
    val feedbackData = Output(new DividerStage2ToStage3(dataWidth, remainderShamtWidth))
  })

  val qds = Module(new QuotientDigitSelector)
  qds.io.truncatedNormalizedDivisor := io.in.normalizedDivisor(dataWidth - 1, dataWidth - 4)
  qds.io.truncatedResidualSum := io.in.residualSum(dataWidth + 3, dataWidth - 4)
  qds.io.truncatedResidualCarry := Cat(io.in.residualCarry, 0.U(4.W))(dataWidth + 3, dataWidth - 4)

  val isNeg = qds.io.isNeg
  val isMag2 = qds.io.isMag2
  val isMag1 = qds.io.isMag1

  val residualAddend = Wire(Vec(dataWidth + 1, Bool()))
  for (i <- residualAddend.indices) {
    residualAddend(i) := (i match {
      case 0 =>
        ((io.in.normalizedDivisor(0) && isMag1) || (false.B && isMag2)) ^ isNeg
      case x if (x == dataWidth) =>
        ((!io.in.normalizedDivisor(dataWidth - 1) && isMag1) ||
          (io.in.normalizedDivisor(dataWidth - 1) && isMag2)) ^ isNeg
      case _ =>
        ((io.in.normalizedDivisor(i) && isMag1) || (io.in.normalizedDivisor(i - 1) && isMag2)) ^ isNeg
    })
  }

  var wallaceReducerInputColumns = Array.fill(dataWidth + 2)(Seq.empty[Bool])
  for (i <- wallaceReducerInputColumns.indices) {
    if (i < io.in.residualSum.getWidth) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ io.in.residualSum(i)
    }
    if ((i >= 4) && ((i - 4) < io.in.residualCarry.getWidth)) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ io.in.residualCarry(i - 4)
    }
    if ((i >= 1) && ((i - 1) < residualAddend.getWidth)) {
      wallaceReducerInputColumns(i) = wallaceReducerInputColumns(i) :+ residualAddend(i - 1)
    }
  }
  wallaceReducerInputColumns(1) = wallaceReducerInputColumns(1) :+ isNeg

  val wallaceReducerCounterUsage = CounterUsage()
  while (wallaceReducerInputColumns.map(_.size).max > 2) {
    wallaceReducerInputColumns = WallaceReducerCarrySave(wallaceReducerInputColumns, wallaceReducerCounterUsage)
  }

  val wallaceReducerOutputRow1 = Cat(wallaceReducerInputColumns.toSeq.reverse.map(_.lift(0).getOrElse(false.B)))
  val wallaceReducerOutputRow2 = Cat(wallaceReducerInputColumns.toSeq.reverse.map(_.lift(1).getOrElse(false.B)))

  val nextAccumulatedQuotientUpper = Mux(
    decoder(
      minimizer = QMCMinimizer,
      input = Cat(isNeg, isMag2, isMag1),
      truthTable = TruthTable(
        table = Seq(
          BitPat("b110") -> BitPat("b1"), // q = +2
          BitPat("b101") -> BitPat("b1"), // q = +1
          BitPat("b000") -> BitPat("b1"), // q =  0
          BitPat("b100") -> BitPat("b1"), // q =  0
          BitPat("b001") -> BitPat("b0"), // q = -1
          BitPat("b010") -> BitPat("b0") // q = -2
        ),
        default = BitPat.dontCare(1)
      )
    ).asBool,
    io.in.accumulatedQuotient,
    io.in.accumulatedQuotientMinusUlp
  )
  val nextAccumulatedQuotientLower = decoder(
    minimizer = QMCMinimizer,
    input = Cat(isNeg, isMag2, isMag1),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b110") -> BitPat("b10"), // q = +2
        BitPat("b101") -> BitPat("b01"), // q = +1
        BitPat("b000") -> BitPat("b00"), // q =  0
        BitPat("b100") -> BitPat("b00"), // q =  0
        BitPat("b001") -> BitPat("b11"), // q = -1
        BitPat("b010") -> BitPat("b10") // q = -2
      ),
      default = BitPat.dontCare(2)
    )
  )
  val nextAccumulatedQuotient = Cat(nextAccumulatedQuotientUpper, nextAccumulatedQuotientLower)

  val nextAccumulatedQuotientMinusUlpUpper = Mux(
    decoder(
      minimizer = QMCMinimizer,
      input = Cat(isNeg, isMag2, isMag1),
      truthTable = TruthTable(
        table = Seq(
          BitPat("b110") -> BitPat("b1"), // q = +2
          BitPat("b101") -> BitPat("b1"), // q = +1
          BitPat("b000") -> BitPat("b0"), // q =  0
          BitPat("b100") -> BitPat("b0"), // q =  0
          BitPat("b001") -> BitPat("b0"), // q = -1
          BitPat("b010") -> BitPat("b0") // q = -2
        ),
        default = BitPat.dontCare(1)
      )
    ).asBool,
    io.in.accumulatedQuotient(dataWidth - 5, 0),
    io.in.accumulatedQuotientMinusUlp(dataWidth - 5, 0)
  )
  val nextAccumulatedQuotientMinusUlpLower = decoder(
    minimizer = QMCMinimizer,
    input = Cat(isNeg, isMag2, isMag1),
    truthTable = TruthTable(
      table = Seq(
        BitPat("b110") -> BitPat("b01"), // q = +2
        BitPat("b101") -> BitPat("b00"), // q = +1
        BitPat("b000") -> BitPat("b11"), // q =  0
        BitPat("b100") -> BitPat("b11"), // q =  0
        BitPat("b001") -> BitPat("b10"), // q = -1
        BitPat("b010") -> BitPat("b01") // q = -2
      ),
      default = BitPat.dontCare(2)
    )
  )
  val nextAccumulatedQuotientMinusUlp = Cat(nextAccumulatedQuotientMinusUlpUpper, nextAccumulatedQuotientMinusUlpLower)

  val finalResidualSum = Mux(
    io.in.isDivisorZero || io.in.isDivisorNeg1 || io.in.isDivisorPos1,
    io.in.residualSum(dataWidth + 1, 1) & Replicate(dataWidth + 1, io.in.isDivisorZero),
    wallaceReducerOutputRow1(dataWidth + 1, 1) // General case: Use Wallace reducer row1 output, discard 1 LSB
  )

  val finalResidualCarry = Mux(
    io.in.isDivisorZero || io.in.isDivisorNeg1 || io.in.isDivisorPos1,
    0.U(dataWidth.W),
    wallaceReducerOutputRow2(dataWidth + 1, 2) // General case: Use Wallace reducer row2 output, discard 2 LSBs
  )

  val finalProvisionalQuotient = Mux(
    io.in.isDivisorZero || io.in.isDivisorNeg1 || io.in.isDivisorPos1,
    ((io.in.residualSum(dataWidth, 1) ^ Replicate(dataWidth, io.in.isDivisorNeg1)) + io.in.isDivisorNeg1) |
      Replicate(dataWidth, io.in.isDivisorZero),
    nextAccumulatedQuotient
  )

  io.out.dividendSign := io.in.dividendSign
  io.out.normalizedDivisor := io.in.normalizedDivisor
  io.out.residualSum := finalResidualSum
  io.out.residualCarry := finalResidualCarry
  io.out.provisionalQuotient := finalProvisionalQuotient
  io.out.remainderShamt := io.in.remainderShamt

  io.feedbackData := io.in
  io.feedbackData.residualSum := Cat(wallaceReducerOutputRow1, 0.U(2.W))
  io.feedbackData.residualCarry := wallaceReducerOutputRow2(dataWidth + 1, 2)
  io.feedbackData.accumulatedQuotient := nextAccumulatedQuotient(dataWidth - 3, 0)
  io.feedbackData.accumulatedQuotientMinusUlp := nextAccumulatedQuotientMinusUlp
}

class DividerStage4(dataWidth: Int, remainderShamtWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new DividerStage3ToStage4(dataWidth, remainderShamtWidth))
    val out = Output(new DividerStage4Output(dataWidth))
  })

  val invertedDivisorSign = io.in.normalizedDivisor(dataWidth - 1)

  val provisionalRemainder = Cat(
    io.in.residualSum(dataWidth, 1) + io.in.residualCarry,
    io.in.residualSum(0)
  )

  val provisionalRemainderSign = provisionalRemainder(dataWidth)

  val isCorrectionNeeded = provisionalRemainder.orR && (provisionalRemainderSign =/= io.in.dividendSign)

  val isRemPlusDiv = provisionalRemainderSign === invertedDivisorSign

  var remPlusDivColumns = Array.fill(dataWidth + 1)(Seq.empty[Bool])
  for (i <- remPlusDivColumns.indices) {
    if (i < io.in.residualSum.getWidth) {
      remPlusDivColumns(i) = remPlusDivColumns(i) :+ io.in.residualSum(i)
    }
    if ((i >= 1) && ((i - 1) < io.in.residualCarry.getWidth)) {
      remPlusDivColumns(i) = remPlusDivColumns(i) :+ io.in.residualCarry(i - 1)
    }
    if (i < io.in.normalizedDivisor.getWidth) {
      remPlusDivColumns(i) = remPlusDivColumns(i) :+ io.in.normalizedDivisor(i)
    }
  }
  remPlusDivColumns(dataWidth) = remPlusDivColumns(dataWidth) :+ !invertedDivisorSign

  var remMinusDivColumns = Array.fill(dataWidth + 1)(Seq.empty[Bool])
  for (i <- remMinusDivColumns.indices) {
    if (i < io.in.residualSum.getWidth) {
      remMinusDivColumns(i) = remMinusDivColumns(i) :+ io.in.residualSum(i)
    }
    if ((i >= 1) && ((i - 1) < io.in.residualCarry.getWidth)) {
      remMinusDivColumns(i) = remMinusDivColumns(i) :+ io.in.residualCarry(i - 1)
    }
    if (i < io.in.normalizedDivisor.getWidth) {
      remMinusDivColumns(i) = remMinusDivColumns(i) :+ !io.in.normalizedDivisor(i)
    }
  }
  remMinusDivColumns(dataWidth) = remMinusDivColumns(dataWidth) :+ invertedDivisorSign
  remMinusDivColumns(0) = remMinusDivColumns(0) :+ true.B

  val wallaceReducerCounterUsage = CounterUsage()

  while (remPlusDivColumns.map(_.size).max > 2) {
    remPlusDivColumns = WallaceReducerCarrySave(remPlusDivColumns, wallaceReducerCounterUsage)
  }
  val remainderPlusDivisor = FinalAdder(remPlusDivColumns)

  while (remMinusDivColumns.map(_.size).max > 2) {
    remMinusDivColumns = WallaceReducerCarrySave(remMinusDivColumns, wallaceReducerCounterUsage)
  }
  val remainderMinusDivisor = FinalAdder(remMinusDivColumns)

  val correctedRemainderBeforeShift = Mux(
    isCorrectionNeeded,
    Mux(
      isRemPlusDiv,
      remainderPlusDivisor,
      remainderMinusDivisor
    ),
    provisionalRemainder
  )

  val correctedRemainder = (correctedRemainderBeforeShift.asSInt >> io.in.remainderShamt)(dataWidth - 1, 0)

  val correctedQuotient = Mux(
    isCorrectionNeeded,
    io.in.provisionalQuotient + Cat(Replicate(dataWidth - 1, isRemPlusDiv), true.B),
    io.in.provisionalQuotient
  )

  io.out.quotient := correctedQuotient
  io.out.remainder := correctedRemainder
}

class PostDividerStage(dataWidth: Int, tagWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(new Bundle {
      val data = new DividerStage4Output(dataWidth)
      val metadata = new DividerMetadata(tagWidth)
    })
    val out = Output(new PostDividerStageOutput(dataWidth, tagWidth))
  })

  val halfWidth = dataWidth >> 1

  val selectedOut = Mux(
    io.in.metadata.isRemainder,
    io.in.data.remainder,
    io.in.data.quotient
  )

  val finalOut = Cat(
    Mux(
      io.in.metadata.isHalfWidth,
      Replicate(halfWidth, selectedOut(halfWidth - 1)),
      selectedOut(dataWidth - 1, halfWidth)
    ),
    selectedOut(halfWidth - 1, 0)
  )

  io.out.out := finalOut
  io.out.tag := io.in.metadata.tag
}

class Radix4SRTDivider[T <: Data](dataWidth: Int, metadataGen: () => T) extends Module {
  val clzWidth = log2Ceil(dataWidth + 1) // '0' to 'dataWidth'
  val clzDiffWidth = log2Ceil(dataWidth) + 1 // '-dataWidth' to 'dataWidth - 2'
  val remainderShamtWidth = log2Ceil(dataWidth) // '0' to 'dataWidth - 1'
  val iterationWidth = log2Ceil(dataWidth - 2) // '-1' to 'ceil((dataWidth - 2) >> 1) - 1'

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val data = new DividerStage1Input(dataWidth)
      val metadata = metadataGen()
    }))
    val resp = Decoupled(new Bundle {
      val data = new DividerStage4Output(dataWidth)
      val metadata = metadataGen()
    })
  })

  class Stage1ToStage2 extends Bundle {
    val data = new DividerStage1ToStage2(dataWidth, clzWidth)
    val metadata = metadataGen()
  }

  class Stage2ToStage3 extends Bundle {
    val data = new DividerStage2ToStage3(dataWidth, remainderShamtWidth)
    val metadata = metadataGen()
  }

  class Stage3ToStage4 extends Bundle {
    val data = new DividerStage3ToStage4(dataWidth, remainderShamtWidth)
    val metadata = metadataGen()
  }

  val stage1 = Module(new DividerStage1(dataWidth, clzWidth))
  val buffer1 = Module(new SkidBuffer(new Stage1ToStage2))
  val stage2 = Module(new DividerStage2(dataWidth, clzWidth, clzDiffWidth, remainderShamtWidth, iterationWidth))
  val buffer2 = Module(new IterativeSkidBuffer(new Stage2ToStage3, iterationWidth))
  val stage3 = Module(new DividerStage3(dataWidth, remainderShamtWidth))
  val buffer3 = Module(new SkidBuffer(new Stage3ToStage4))
  val stage4 = Module(new DividerStage4(dataWidth, remainderShamtWidth))

  stage1.io.in := io.req.bits.data
  buffer1.io.enq.bits.data := stage1.io.out
  buffer1.io.enq.bits.metadata := io.req.bits.metadata
  buffer1.io.enq.valid := io.req.valid
  io.req.ready := buffer1.io.enq.ready

  stage2.io.in := buffer1.io.deq.bits.data
  buffer2.io.enq.bits.data := stage2.io.out
  buffer2.io.enq.bits.metadata := buffer1.io.deq.bits.metadata
  buffer2.io.enq.valid := buffer1.io.deq.valid
  buffer1.io.deq.ready := buffer2.io.enq.ready
  buffer2.io.feedbackData.data := stage3.io.feedbackData
  buffer2.io.feedbackData.metadata := buffer2.io.deq.bits.metadata
  buffer2.io.totalIterationsMinus2 := stage2.io.totalIterationsMinus2

  stage3.io.in := buffer2.io.deq.bits.data
  buffer3.io.enq.bits.data := stage3.io.out
  buffer3.io.enq.bits.metadata := buffer2.io.deq.bits.metadata
  buffer3.io.enq.valid := buffer2.io.deq.valid
  buffer2.io.deq.ready := buffer3.io.enq.ready

  stage4.io.in := buffer3.io.deq.bits.data
  io.resp.bits.data := stage4.io.out
  io.resp.bits.metadata := buffer3.io.deq.bits.metadata
  io.resp.valid := buffer3.io.deq.valid
  buffer3.io.deq.ready := io.resp.ready
}

object Radix4SRTDivider {
  def apply[T <: Data](dataWidth: Int, metadataGen: () => T): Radix4SRTDivider[T] = {
    new Radix4SRTDivider(dataWidth, metadataGen)
  }

  def apply(dataWidth: Int): Radix4SRTDivider[Bundle] = {
    new Radix4SRTDivider(dataWidth, () => new Bundle {})
  }
}

class RISCVDivider(dataWidth: Int = 64, numXPRs: Int = 32) extends Module {
  val tagWidth = log2Ceil(numXPRs)

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PreDividerStageInput(dataWidth, tagWidth)))
    val resp = Decoupled(new PostDividerStageOutput(dataWidth, tagWidth))
  })

  val preStage = Module(new PreDividerStage(dataWidth, tagWidth))
  val coreDiv = Module(new Radix4SRTDivider(dataWidth, () => new DividerMetadata(tagWidth)))
  val postStage = Module(new PostDividerStage(dataWidth, tagWidth))

  preStage.io.in := io.req.bits
  coreDiv.io.req.bits.data := preStage.io.out.data
  coreDiv.io.req.bits.metadata := preStage.io.out.metadata
  coreDiv.io.req.valid := io.req.valid
  io.req.ready := coreDiv.io.req.ready

  postStage.io.in.data := coreDiv.io.resp.bits.data
  postStage.io.in.metadata := coreDiv.io.resp.bits.metadata
  io.resp.bits := postStage.io.out
  io.resp.valid := coreDiv.io.resp.valid
  coreDiv.io.resp.ready := io.resp.ready
}
