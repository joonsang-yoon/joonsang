package HardFloat.test

import chisel3._
import chisel3.util._
import HardFloat._

class ValExec_RecFNToUIN(expWidth: Int, sigWidth: Int, intWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt((expWidth + sigWidth).W))
    val roundingMode = Input(UInt(3.W))

    val expected = new Bundle {
      val out = Input(UInt(intWidth.W))
      val exceptionFlags = Input(UInt(5.W))
    }

    val actual = new Bundle {
      val out = Output(UInt(intWidth.W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val recFNToIN = Module(new RecFNToIN(expWidth, sigWidth, intWidth))
  recFNToIN.io.in := RecFNFromFN(expWidth, sigWidth, io.in)
  recFNToIN.io.roundingMode := io.roundingMode
  recFNToIN.io.signedOut := false.B

  io.actual.out := recFNToIN.io.out
  io.actual.exceptionFlags := Cat(
    recFNToIN.io.intExceptionFlags(2, 1).orR,
    0.U(3.W),
    recFNToIN.io.intExceptionFlags(0)
  )

  io.check := true.B
  io.pass := (io.actual.out === io.expected.out) && (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_RecFNToIN(expWidth: Int, sigWidth: Int, intWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt((expWidth + sigWidth).W))
    val roundingMode = Input(UInt(3.W))

    val expected = new Bundle {
      val out = Input(UInt(intWidth.W))
      val exceptionFlags = Input(UInt(5.W))
    }

    val actual = new Bundle {
      val out = Output(UInt(intWidth.W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val recFNToIN = Module(new RecFNToIN(expWidth, sigWidth, intWidth))
  recFNToIN.io.in := RecFNFromFN(expWidth, sigWidth, io.in)
  recFNToIN.io.roundingMode := io.roundingMode
  recFNToIN.io.signedOut := true.B

  io.actual.out := recFNToIN.io.out
  io.actual.exceptionFlags := Cat(
    recFNToIN.io.intExceptionFlags(2, 1).orR,
    0.U(3.W),
    recFNToIN.io.intExceptionFlags(0)
  )

  io.check := true.B
  io.pass := (io.actual.out === io.expected.out) && (io.actual.exceptionFlags === io.expected.exceptionFlags)
}
