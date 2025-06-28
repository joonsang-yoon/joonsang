package HardFloat.test

import chisel3._
import HardFloat._

class ValExec_UINToRecFN(intWidth: Int, expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(intWidth.W))
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

  val iNToRecFN = Module(new INToRecFN(intWidth, expWidth, sigWidth))
  iNToRecFN.io.signedIn := false.B
  iNToRecFN.io.in := io.in
  iNToRecFN.io.roundingMode := io.roundingMode
  iNToRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, io.expected.out)

  io.actual.out := iNToRecFN.io.out
  io.actual.exceptionFlags := iNToRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_INToRecFN(intWidth: Int, expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(intWidth.W))
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

  val iNToRecFN = Module(new INToRecFN(intWidth, expWidth, sigWidth))
  iNToRecFN.io.signedIn := true.B
  iNToRecFN.io.in := io.in
  iNToRecFN.io.roundingMode := io.roundingMode
  iNToRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(expWidth, sigWidth, io.expected.out)

  io.actual.out := iNToRecFN.io.out
  io.actual.exceptionFlags := iNToRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(expWidth, sigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
