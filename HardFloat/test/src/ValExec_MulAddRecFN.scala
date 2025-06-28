package HardFloat.test

import chisel3._
import chisel3.util._
import HardFloat._

class ValExec_MulAddRecFN(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))
    val b = Input(UInt((expWidth + sigWidth).W))
    val c = Input(UInt((expWidth + sigWidth).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())

    val expected = new Bundle {
      val out = Input(UInt((expWidth + sigWidth).W))
      val exceptionFlags = Input(UInt(5.W))
      val recOut = Output(UInt((expWidth + sigWidth + 1).W))
    }

    val actual = new Bundle {
      val out = Output(UInt((expWidth + sigWidth + 1).W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val mulAddRecFN = Module(new MulAddRecFN(expWidth, sigWidth))
  mulAddRecFN.io.op := 0.U
  mulAddRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  mulAddRecFN.io.b := RecFNFromFN(expWidth, sigWidth, io.b)
  mulAddRecFN.io.c := RecFNFromFN(expWidth, sigWidth, io.c)
  mulAddRecFN.io.roundingMode := io.roundingMode
  mulAddRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, io.expected.out)

  io.actual.out := mulAddRecFN.io.out
  io.actual.exceptionFlags := mulAddRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_MulAddRecFN_add(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))
    val b = Input(UInt((expWidth + sigWidth).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())

    val expected = new Bundle {
      val out = Input(UInt((expWidth + sigWidth).W))
      val exceptionFlags = Input(UInt(5.W))
      val recOut = Output(UInt((expWidth + sigWidth + 1).W))
    }

    val actual = new Bundle {
      val out = Output(UInt((expWidth + sigWidth + 1).W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val mulAddRecFN = Module(new MulAddRecFN(expWidth, sigWidth))
  mulAddRecFN.io.op := 0.U
  mulAddRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  mulAddRecFN.io.b := (BigInt(1) << (expWidth + sigWidth - 1)).U
  mulAddRecFN.io.c := RecFNFromFN(expWidth, sigWidth, io.b)
  mulAddRecFN.io.roundingMode := io.roundingMode
  mulAddRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, io.expected.out)

  io.actual.out := mulAddRecFN.io.out
  io.actual.exceptionFlags := mulAddRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_MulAddRecFN_mul(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))
    val b = Input(UInt((expWidth + sigWidth).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())

    val expected = new Bundle {
      val out = Input(UInt((expWidth + sigWidth).W))
      val exceptionFlags = Input(UInt(5.W))
      val recOut = Output(UInt((expWidth + sigWidth + 1).W))
    }

    val actual = new Bundle {
      val out = Output(UInt((expWidth + sigWidth + 1).W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val mulAddRecFN = Module(new MulAddRecFN(expWidth, sigWidth))
  mulAddRecFN.io.op := 0.U
  mulAddRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  mulAddRecFN.io.b := RecFNFromFN(expWidth, sigWidth, io.b)
  mulAddRecFN.io.c := Cat(io.a(expWidth + sigWidth - 1) ^ io.b(expWidth + sigWidth - 1), 0.U((expWidth + sigWidth).W))
  mulAddRecFN.io.roundingMode := io.roundingMode
  mulAddRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, io.expected.out)

  io.actual.out := mulAddRecFN.io.out
  io.actual.exceptionFlags := mulAddRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
