package HardFloat.test

import chisel3._
import chisel3.util._
import HardFloat._

class MulAddRecFN_io(expWidth: Int, sigWidth: Int) extends Bundle {
  val a = UInt((expWidth + sigWidth).W)
  val b = UInt((expWidth + sigWidth).W)
  val c = UInt((expWidth + sigWidth).W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt((expWidth + sigWidth).W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_MulAddRecFN(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new MulAddRecFN_io(expWidth, sigWidth)))

    val output = new Bundle {
      val a = Output(UInt((expWidth + sigWidth).W))
      val b = Output(UInt((expWidth + sigWidth).W))
      val c = Output(UInt((expWidth + sigWidth).W))
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

  val ma = Module(new MulAddRecFN(expWidth, sigWidth, 3))
  val cq = Module(new Queue(new MulAddRecFN_io(expWidth, sigWidth), 5))

  cq.io.enq.valid := io.input.valid && ma.io.req.ready
  cq.io.enq.bits := io.input.bits

  io.input.ready := ma.io.req.ready && cq.io.enq.ready
  ma.io.req.valid := io.input.valid && cq.io.enq.ready
  ma.io.req.bits.op := 0.U
  ma.io.req.bits.a := RecFNFromFN(expWidth, sigWidth, io.input.bits.a)
  ma.io.req.bits.b := RecFNFromFN(expWidth, sigWidth, io.input.bits.b)
  ma.io.req.bits.c := RecFNFromFN(expWidth, sigWidth, io.input.bits.c)
  ma.io.req.bits.roundingMode := io.input.bits.roundingMode
  ma.io.detectTininess := io.input.bits.detectTininess

  io.output.a := cq.io.deq.bits.a
  io.output.b := cq.io.deq.bits.b
  io.output.c := cq.io.deq.bits.c
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, cq.io.deq.bits.out)

  io.actual.out := ma.io.resp.bits.out
  io.actual.exceptionFlags := ma.io.resp.bits.exceptionFlags

  ma.io.resp.ready := cq.io.deq.valid
  cq.io.deq.ready := ma.io.resp.valid

  io.check := ma.io.resp.valid && cq.io.deq.valid
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class MulAddRecFN_add_io(expWidth: Int, sigWidth: Int) extends Bundle {
  val a = UInt((expWidth + sigWidth).W)
  val b = UInt((expWidth + sigWidth).W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt((expWidth + sigWidth).W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_MulAddRecFN_add(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new MulAddRecFN_add_io(expWidth, sigWidth)))

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

  val ma = Module(new MulAddRecFN(expWidth, sigWidth, 3))
  val cq = Module(new Queue(new MulAddRecFN_add_io(expWidth, sigWidth), 5))

  cq.io.enq.valid := io.input.valid && ma.io.req.ready
  cq.io.enq.bits := io.input.bits

  io.input.ready := ma.io.req.ready && cq.io.enq.ready
  ma.io.req.valid := io.input.valid && cq.io.enq.ready
  ma.io.req.bits.op := 0.U
  ma.io.req.bits.a := RecFNFromFN(expWidth, sigWidth, io.input.bits.a)
  ma.io.req.bits.b := (BigInt(1) << (expWidth + sigWidth - 1)).U
  ma.io.req.bits.c := RecFNFromFN(expWidth, sigWidth, io.input.bits.b)
  ma.io.req.bits.roundingMode := io.input.bits.roundingMode
  ma.io.detectTininess := io.input.bits.detectTininess

  io.output.a := cq.io.deq.bits.a
  io.output.b := cq.io.deq.bits.b
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, cq.io.deq.bits.out)

  io.actual.out := ma.io.resp.bits.out
  io.actual.exceptionFlags := ma.io.resp.bits.exceptionFlags

  ma.io.resp.ready := cq.io.deq.valid
  cq.io.deq.ready := ma.io.resp.valid

  io.check := ma.io.resp.valid && cq.io.deq.valid
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class MulAddRecFN_mul_io(expWidth: Int, sigWidth: Int) extends Bundle {
  val a = UInt((expWidth + sigWidth).W)
  val b = UInt((expWidth + sigWidth).W)
  val roundingMode = UInt(3.W)
  val detectTininess = Bool()
  val out = UInt((expWidth + sigWidth).W)
  val exceptionFlags = UInt(5.W)
}

class ValExec_MulAddRecFN_mul(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new MulAddRecFN_mul_io(expWidth, sigWidth)))

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

  val ma = Module(new MulAddRecFN(expWidth, sigWidth, 3))
  val cq = Module(new Queue(new MulAddRecFN_mul_io(expWidth, sigWidth), 5))

  cq.io.enq.valid := io.input.valid && ma.io.req.ready
  cq.io.enq.bits := io.input.bits

  io.input.ready := ma.io.req.ready && cq.io.enq.ready
  ma.io.req.valid := io.input.valid && cq.io.enq.ready
  ma.io.req.bits.op := 0.U
  ma.io.req.bits.a := RecFNFromFN(expWidth, sigWidth, io.input.bits.a)
  ma.io.req.bits.b := RecFNFromFN(expWidth, sigWidth, io.input.bits.b)
  ma.io.req.bits.c := Cat(
    io.input.bits.a(expWidth + sigWidth - 1) ^ io.input.bits.b(expWidth + sigWidth - 1),
    0.U((expWidth + sigWidth).W)
  )
  ma.io.req.bits.roundingMode := io.input.bits.roundingMode
  ma.io.detectTininess := io.input.bits.detectTininess

  io.output.a := cq.io.deq.bits.a
  io.output.b := cq.io.deq.bits.b
  io.output.roundingMode := cq.io.deq.bits.roundingMode
  io.output.detectTininess := cq.io.deq.bits.detectTininess

  io.expected.out := cq.io.deq.bits.out
  io.expected.exceptionFlags := cq.io.deq.bits.exceptionFlags
  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, cq.io.deq.bits.out)

  io.actual.out := ma.io.resp.bits.out
  io.actual.exceptionFlags := ma.io.resp.bits.exceptionFlags

  ma.io.resp.ready := cq.io.deq.valid
  cq.io.deq.ready := ma.io.resp.valid

  io.check := ma.io.resp.valid && cq.io.deq.valid
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
