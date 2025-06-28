package HardFloat.test

import chisel3._
import HardFloat._

class ValExec_CompareRecFN_lt(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))
    val b = Input(UInt((expWidth + sigWidth).W))

    val expected = new Bundle {
      val out = Input(Bool())
      val exceptionFlags = Input(UInt(5.W))
    }

    val actual = new Bundle {
      val out = Output(Bool())
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val compareRecFN = Module(new CompareRecFN(expWidth, sigWidth))
  compareRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  compareRecFN.io.b := RecFNFromFN(expWidth, sigWidth, io.b)
  compareRecFN.io.signaling := true.B

  io.actual.out := compareRecFN.io.lt
  io.actual.exceptionFlags := compareRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := (io.actual.out === io.expected.out) && (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_CompareRecFN_le(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))
    val b = Input(UInt((expWidth + sigWidth).W))

    val expected = new Bundle {
      val out = Input(Bool())
      val exceptionFlags = Input(UInt(5.W))
    }

    val actual = new Bundle {
      val out = Output(Bool())
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val compareRecFN = Module(new CompareRecFN(expWidth, sigWidth))
  compareRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  compareRecFN.io.b := RecFNFromFN(expWidth, sigWidth, io.b)
  compareRecFN.io.signaling := true.B

  io.actual.out := compareRecFN.io.lt || compareRecFN.io.eq
  io.actual.exceptionFlags := compareRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := (io.actual.out === io.expected.out) && (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_CompareRecFN_eq(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))
    val b = Input(UInt((expWidth + sigWidth).W))

    val expected = new Bundle {
      val out = Input(Bool())
      val exceptionFlags = Input(UInt(5.W))
    }

    val actual = new Bundle {
      val out = Output(Bool())
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val compareRecFN = Module(new CompareRecFN(expWidth, sigWidth))
  compareRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  compareRecFN.io.b := RecFNFromFN(expWidth, sigWidth, io.b)
  compareRecFN.io.signaling := false.B

  io.actual.out := compareRecFN.io.eq
  io.actual.exceptionFlags := compareRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := (io.actual.out === io.expected.out) && (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
