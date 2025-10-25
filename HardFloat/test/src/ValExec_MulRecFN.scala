package HardFloat.test

import chisel3._
import HardFloat._

class ValExec_MulRecFN(expWidth: Int, sigWidth: Int) extends Module {
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

  val mulRecFN = Module(new MulRecFN(expWidth, sigWidth))
  mulRecFN.io.a := RecFNFromFN(expWidth, sigWidth, io.a)
  mulRecFN.io.b := RecFNFromFN(expWidth, sigWidth, io.b)
  mulRecFN.io.roundingMode := io.roundingMode
  mulRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, io.expected.out)

  io.actual.out := mulRecFN.io.out
  io.actual.exceptionFlags := mulRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
