package HardFloat.test

import HardFloat._
import chisel3._
import chisel3.util._

class DivRecF64_io extends Bundle {
  val a = UInt(64.W)
  val b = UInt(64.W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt(64.W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_DivSqrtRecF64_div extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DivRecF64_io))

    val output = new Bundle {
      val a = Output(UInt(64.W))
      val b = Output(UInt(64.W))
      val roundingMode = Output(UInt(3.W))
      val detectTininess = Output(Bool())
    }

    val expected = new Bundle {
      val out = Output(UInt(64.W))
      val exceptionFlags = Output(UInt(5.W))
      val recOut = Output(UInt(65.W))
    }

    val actual = new Bundle {
      val out = Output(UInt(65.W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val ds = Module(new DivSqrtRecF64)
  val cq = Module(new Queue(new DivRecF64_io, 5))

  cq.io.enq.valid := io.input.valid && ds.io.inReady_div
  cq.io.enq.bits := io.input.bits

  io.input.ready := ds.io.inReady_div && cq.io.enq.ready
  ds.io.inValid := io.input.valid && cq.io.enq.ready
  ds.io.sqrtOp := false.B
  ds.io.a := RecFNFromFN(11, 53, io.input.bits.a)
  ds.io.b := RecFNFromFN(11, 53, io.input.bits.b)
  ds.io.roundingMode := io.input.bits.roundingMode
  ds.io.detectTininess := io.input.bits.detectTininess

  io.output.a := cq.io.deq.bits.a
  io.output.b := cq.io.deq.bits.b
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(11, 53, cq.io.deq.bits.out)

  io.actual.out := ds.io.out
  io.actual.exceptionFlags := ds.io.exceptionFlags

  cq.io.deq.ready := ds.io.outValid_div

  io.check := ds.io.outValid_div
  io.pass := cq.io.deq.valid && EquivRecFN(11, 53, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class SqrtRecF64_io extends Bundle {
  val b = UInt(64.W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt(64.W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_DivSqrtRecF64_sqrt extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new SqrtRecF64_io))

    val output = new Bundle {
      val b = Output(UInt(64.W))
      val roundingMode = Output(UInt(3.W))
      val detectTininess = Output(Bool())
    }

    val expected = new Bundle {
      val out = Output(UInt(64.W))
      val exceptionFlags = Output(UInt(5.W))
      val recOut = Output(UInt(65.W))
    }

    val actual = new Bundle {
      val out = Output(UInt(65.W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val ds = Module(new DivSqrtRecF64)
  val cq = Module(new Queue(new SqrtRecF64_io, 5))

  cq.io.enq.valid := io.input.valid && ds.io.inReady_sqrt
  cq.io.enq.bits := io.input.bits

  io.input.ready := ds.io.inReady_sqrt && cq.io.enq.ready
  ds.io.inValid := io.input.valid && cq.io.enq.ready
  ds.io.sqrtOp := true.B
  ds.io.b := RecFNFromFN(11, 53, io.input.bits.b)
  ds.io.a := DontCare
  ds.io.roundingMode := io.input.bits.roundingMode
  ds.io.detectTininess := io.input.bits.detectTininess

  io.output.b := cq.io.deq.bits.b
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(11, 53, cq.io.deq.bits.out)

  io.actual.exceptionFlags := ds.io.exceptionFlags
  io.actual.out := ds.io.out

  cq.io.deq.ready := ds.io.outValid_sqrt

  io.check := ds.io.outValid_sqrt
  io.pass := cq.io.deq.valid && EquivRecFN(11, 53, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
