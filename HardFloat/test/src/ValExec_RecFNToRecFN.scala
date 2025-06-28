package HardFloat.test

import chisel3._
import HardFloat._

class ValExec_RecFNToRecFN(inExpWidth: Int, inSigWidth: Int, outExpWidth: Int, outSigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt((inExpWidth + inSigWidth).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())

    val expected = new Bundle {
      val out = Input(UInt((outExpWidth + outSigWidth).W))
      val exceptionFlags = Input(UInt(5.W))
      val recOut = Output(UInt((outExpWidth + outSigWidth + 1).W))
    }

    val actual = new Bundle {
      val out = Output(UInt((outExpWidth + outSigWidth + 1).W))
      val exceptionFlags = Output(UInt(5.W))
    }

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  val recFNToRecFN = Module(new RecFNToRecFN(inExpWidth, inSigWidth, outExpWidth, outSigWidth))
  recFNToRecFN.io.in := RecFNFromFN(inExpWidth, inSigWidth, io.in)
  recFNToRecFN.io.roundingMode := io.roundingMode
  recFNToRecFN.io.detectTininess := io.detectTininess

  io.expected.recOut := RecFNFromFN(outExpWidth, outSigWidth, io.expected.out)

  io.actual.out := recFNToRecFN.io.out
  io.actual.exceptionFlags := recFNToRecFN.io.exceptionFlags

  io.check := true.B
  io.pass := EquivRecFN(outExpWidth, outSigWidth, io.actual.out, io.expected.recOut) &&
    (io.actual.exceptionFlags === io.expected.exceptionFlags)
}

class ValExec_RecF16ToRecF32 extends ValExec_RecFNToRecFN(5, 11, 8, 24)
class ValExec_RecF16ToRecF64 extends ValExec_RecFNToRecFN(5, 11, 11, 53)
class ValExec_RecF32ToRecF16 extends ValExec_RecFNToRecFN(8, 24, 5, 11)
class ValExec_RecF32ToRecF64 extends ValExec_RecFNToRecFN(8, 24, 11, 53)
class ValExec_RecF64ToRecF16 extends ValExec_RecFNToRecFN(11, 53, 5, 11)
class ValExec_RecF64ToRecF32 extends ValExec_RecFNToRecFN(11, 53, 8, 24)
