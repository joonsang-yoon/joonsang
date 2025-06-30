package HardFloat.test

import HardFloat._
import chisel3._
import chisel3.util._

class DivRecFN_io(expWidth: Int, sigWidth: Int) extends Bundle {
  val a = UInt((expWidth + sigWidth).W)
  val b = UInt((expWidth + sigWidth).W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt((expWidth + sigWidth).W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_DivSqrtRecFN_small_div(expWidth: Int, sigWidth: Int, options: Int) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new DivRecFN_io(expWidth, sigWidth)))

    val output = new Bundle {
      val a = Output(UInt((expWidth + sigWidth).W))
      val b = Output(UInt((expWidth + sigWidth).W))
      val roundingMode = Output(UInt(3.W))
      val detectTininess = Output(Bool())
    }

    val expected = new Bundle {
      val out = Output(UInt((expWidth + sigWidth).W))
      val exceptionFlags = Output(UInt(5.W))
      val recOut = Output(UInt((expWidth + sigWidth + 1).W))
    }

    val actual = new Bundle {
      val out = Output(UInt((expWidth + sigWidth + 1).W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val ds = Module(new DivSqrtRecFN_small(expWidth, sigWidth, options))
  val cq = Module(new Queue(new DivRecFN_io(expWidth, sigWidth), 5))

  cq.io.enq.valid := io.input.valid && ds.io.inReady
  cq.io.enq.bits := io.input.bits

  io.input.ready := ds.io.inReady && cq.io.enq.ready
  ds.io.inValid := io.input.valid && cq.io.enq.ready
  ds.io.sqrtOp := false.B
  ds.io.a := RecFNFromFN(expWidth, sigWidth, io.input.bits.a)
  ds.io.b := RecFNFromFN(expWidth, sigWidth, io.input.bits.b)
  ds.io.roundingMode := io.input.bits.roundingMode
  ds.io.detectTininess := io.input.bits.detectTininess

  io.output.a := cq.io.deq.bits.a
  io.output.b := cq.io.deq.bits.b
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, cq.io.deq.bits.out)

  io.actual.out := ds.io.out
  io.actual.exceptionFlags := ds.io.exceptionFlags

  cq.io.deq.ready := ds.io.outValid_div

  io.check := ds.io.outValid_div
  io.pass := cq.io.deq.valid && EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class SqrtRecFN_io(expWidth: Int, sigWidth: Int) extends Bundle {
  val a = UInt((expWidth + sigWidth).W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt((expWidth + sigWidth).W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_DivSqrtRecFN_small_sqrt(expWidth: Int, sigWidth: Int, options: Int) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new SqrtRecFN_io(expWidth, sigWidth)))

    val output = new Bundle {
      val a = Output(UInt((expWidth + sigWidth).W))
      val roundingMode = Output(UInt(3.W))
      val detectTininess = Output(Bool())
    }

    val expected = new Bundle {
      val out = Output(UInt((expWidth + sigWidth).W))
      val exceptionFlags = Output(UInt(5.W))
      val recOut = Output(UInt((expWidth + sigWidth + 1).W))
    }

    val actual = new Bundle {
      val out = Output(UInt((expWidth + sigWidth + 1).W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val ds = Module(new DivSqrtRecFN_small(expWidth, sigWidth, options))
  val cq = Module(new Queue(new SqrtRecFN_io(expWidth, sigWidth), 5))

  cq.io.enq.valid := io.input.valid && ds.io.inReady
  cq.io.enq.bits := io.input.bits

  io.input.ready := ds.io.inReady && cq.io.enq.ready
  ds.io.inValid := io.input.valid && cq.io.enq.ready
  ds.io.sqrtOp := true.B
  ds.io.a := RecFNFromFN(expWidth, sigWidth, io.input.bits.a)
  ds.io.b := DontCare
  ds.io.roundingMode := io.input.bits.roundingMode
  ds.io.detectTininess := io.input.bits.detectTininess

  io.output.a := cq.io.deq.bits.a
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, cq.io.deq.bits.out)

  io.actual.exceptionFlags := ds.io.exceptionFlags
  io.actual.out := ds.io.out

  cq.io.deq.ready := ds.io.outValid_sqrt

  io.check := ds.io.outValid_sqrt
  io.pass := cq.io.deq.valid && EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
