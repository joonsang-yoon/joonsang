package HardUtils

import chisel3._
import chisel3.util._

class PipeBuffer[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })

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

class IterativePipeBuffer[T <: Data](gen: T, iterationWidth: Int) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val feedbackData = Input(gen)
    val totalIterationsMinus2 = Input(UInt(iterationWidth.W))
    val deq = Decoupled(gen)
    val remainingIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

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

class SkidBuffer[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })

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

class IterativeSkidBuffer[T <: Data](gen: T, iterationWidth: Int) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val feedbackData = Input(gen)
    val totalIterationsMinus2 = Input(UInt(iterationWidth.W))
    val deq = Decoupled(gen)
    val remainingIterationsMinus2 = Output(UInt(iterationWidth.W))
  })

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
