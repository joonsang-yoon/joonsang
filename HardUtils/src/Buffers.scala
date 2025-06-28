package HardUtils

import chisel3._
import chisel3.util._

/**
  * A simple pipeline buffer that registers valid and data signals.
  *
  * This buffer introduces a one-cycle latency. It will deassert its `ready` signal
  * if it contains valid data and the downstream consumer is not ready.
  *
  * @tparam T The type of the Chisel `Data` being buffered.
  * @param gen The Chisel `Data` type used as the template for the buffer's IO.
  * @param buffered A Boolean flag to enable or bypass the buffer. If false, io.deq <> io.enq.
  */
class PipeBuffer[T <: Data](gen: T, buffered: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })

  if (!buffered) {
    io.deq <> io.enq
  } else {
    val validReg = RegInit(false.B)
    val dataReg = Reg(gen)

    when(io.deq.fire) {
      validReg := false.B
    }
    when(io.enq.fire) {
      validReg := true.B
    }

    when(io.enq.fire) {
      dataReg := io.enq.bits
    }

    io.enq.ready := io.deq.ready || !validReg
    io.deq.valid := validReg
    io.deq.bits := dataReg
  }
}

/**
  * A pipeline buffer designed for iterative operations.
  *
  * It holds the data and accepts feedback for a specified number of iterations
  * before passing it to the next stage. The buffer uses an internal counter to
  * track the remaining iterations.
  *
  * @tparam T The type of the Chisel `Data` being buffered.
  * @param gen The Chisel `Data` type used as the template for the buffer's IO.
  * @param iterationWidth The bit-width of the iteration counter. This must be calculated
  *                       correctly to accommodate the maximum number of iterations.
  *                       For example, in a DivSqrtRecFN, it might be calculated as
  *                       `log2Ceil(sigWidth - 1)` to represent a range from `-1` to
  *                       `ceil((sigWidth - 1) >> 1) - 1`.
  * @param buffered A Boolean flag to enable or bypass the buffer. If false, io.deq <> io.enq.
  */
class IterativePipeBuffer[T <: Data](gen: T, iterationWidth: Int, buffered: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val feedbackData = Input(gen)
    val totalIterationsMinus2 = Input(UInt(iterationWidth.W))
    val deq = Decoupled(gen)
    val remainingIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

  if (!buffered) {
    io.deq <> io.enq
    io.remainingIterationsMinus2 := (-1.S(iterationWidth.W)).asUInt
  } else {
    val validReg = RegInit(false.B)
    val dataReg = Reg(gen)
    val iterReg = RegInit((-1.S(iterationWidth.W)).asUInt)

    when(io.deq.fire) {
      validReg := false.B
    }
    when(io.enq.fire) {
      validReg := true.B
    }

    when(!iterReg(iterationWidth - 1)) {
      dataReg := io.feedbackData
      iterReg := iterReg - 1.U
    }
    when(io.enq.fire) {
      dataReg := io.enq.bits
      iterReg := io.totalIterationsMinus2
    }

    io.enq.ready := (io.deq.ready || !validReg) && iterReg(iterationWidth - 1)
    io.deq.valid := validReg && iterReg(iterationWidth - 1)
    io.deq.bits := dataReg
    io.remainingIterationsMinus2 := iterReg
  }
}

/**
  * A skid buffer module that provides full-throughput pipelining while safely handling backpressure.
  *
  * It uses a primary register and a secondary "skid" register to prevent data loss
  * when the output is stalled, allowing the upstream producer to send one more piece
  * of data after the downstream consumer asserts backpressure.
  *
  * @tparam T The type of the Chisel `Data` being buffered.
  * @param gen The Chisel `Data` type used as the template for the buffer's IO.
  * @param buffered A Boolean flag to enable or bypass the buffer. If false, io.deq <> io.enq.
  */
class SkidBuffer[T <: Data](gen: T, buffered: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })

  if (!buffered) {
    io.deq <> io.enq
  } else {
    val inputReadyReg = RegInit(true.B)
    val outputValidReg = RegInit(false.B)
    val primaryDataReg = Reg(gen)
    val skidDataReg = Reg(gen)

    val outputStageCanUpdate = io.deq.ready || !outputValidReg
    val inputStageCanUpdate = io.enq.valid || !inputReadyReg

    when(inputStageCanUpdate) {
      inputReadyReg := outputStageCanUpdate
    }
    when(outputStageCanUpdate) {
      outputValidReg := inputStageCanUpdate
    }
    io.enq.ready := inputReadyReg
    io.deq.valid := outputValidReg

    when(outputStageCanUpdate && inputStageCanUpdate) {
      primaryDataReg := Mux(inputReadyReg, io.enq.bits, skidDataReg)
    }
    when(io.enq.fire && !outputStageCanUpdate) {
      skidDataReg := io.enq.bits
    }
    io.deq.bits := primaryDataReg
  }
}

/**
  * A skid buffer designed for iterative operations.
  *
  * It combines the full-throughput backpressure handling of a standard skid buffer
  * with the ability to hold and update data over multiple iterations via a feedback loop.
  * It maintains separate iteration counters for both the primary and skid registers.
  *
  * @tparam T The type of the Chisel `Data` being buffered.
  * @param gen The Chisel `Data` type used as the template for the buffer's IO.
  * @param iterationWidth The bit-width of the iteration counter. This must be calculated
  *                       correctly to accommodate the maximum number of iterations.
  *                       For example, in a DivSqrtRecFN, it might be calculated as
  *                       `log2Ceil(sigWidth - 1)` to represent a range from `-1` to
  *                       `ceil((sigWidth - 1) >> 1) - 1`.
  * @param buffered A Boolean flag to enable or bypass the buffer. If false, io.deq <> io.enq.
  */
class IterativeSkidBuffer[T <: Data](gen: T, iterationWidth: Int, buffered: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val feedbackData = Input(gen)
    val totalIterationsMinus2 = Input(UInt(iterationWidth.W))
    val deq = Decoupled(gen)
    val remainingIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

  if (!buffered) {
    io.deq <> io.enq
    io.remainingIterationsMinus2 := (-1.S(iterationWidth.W)).asUInt
  } else {
    val inputReadyReg = RegInit(true.B)
    val outputValidReg = RegInit(false.B)
    val primaryDataReg = Reg(gen)
    val skidDataReg = Reg(gen)
    val primaryIterReg = RegInit((-1.S(iterationWidth.W)).asUInt)
    val skidIterReg = RegInit((-1.S(iterationWidth.W)).asUInt)

    val outputStageCanUpdate = (io.deq.ready || !outputValidReg) && primaryIterReg(iterationWidth - 1)
    val inputStageCanUpdate = io.enq.valid || !inputReadyReg

    when(inputStageCanUpdate) {
      inputReadyReg := outputStageCanUpdate
    }
    when(outputStageCanUpdate) {
      outputValidReg := inputStageCanUpdate
    }
    io.enq.ready := inputReadyReg
    io.deq.valid := outputValidReg && primaryIterReg(iterationWidth - 1)

    when(!primaryIterReg(iterationWidth - 1)) {
      primaryDataReg := io.feedbackData
      primaryIterReg := primaryIterReg - 1.U
    }
    when(outputStageCanUpdate && inputStageCanUpdate) {
      primaryDataReg := Mux(inputReadyReg, io.enq.bits, skidDataReg)
      primaryIterReg := Mux(inputReadyReg, io.totalIterationsMinus2, skidIterReg)
    }
    when(io.enq.fire && !outputStageCanUpdate) {
      skidDataReg := io.enq.bits
      skidIterReg := io.totalIterationsMinus2
    }
    io.deq.bits := primaryDataReg
    io.remainingIterationsMinus2 := primaryIterReg
  }
}
