package HardFloat

import chisel3._
import chisel3.util._
import Consts._

class RecFNToRecFN(inExpWidth: Int, inSigWidth: Int, outExpWidth: Int, outSigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val in = Input(UInt((inExpWidth + inSigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((outExpWidth + outSigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val rawIn = RawFloatFromRecFN(inExpWidth, inSigWidth, io.in)

  if ((inExpWidth == outExpWidth) && (inSigWidth <= outSigWidth)) {
    io.out := Cat(io.in, 0.U((outSigWidth - inSigWidth).W))
    io.exceptionFlags := Cat(IsSigNaNRawFloat(rawIn), 0.U(4.W))
  } else {
    val roundAnyRawFNToRecFN = Module(
      new RoundAnyRawFNToRecFN(
        inExpWidth,
        inSigWidth,
        outExpWidth,
        outSigWidth,
        flRoundOpt_sigMSBitAlwaysZero
      )
    )
    roundAnyRawFNToRecFN.io.invalidExc := IsSigNaNRawFloat(rawIn)
    roundAnyRawFNToRecFN.io.infiniteExc := false.B
    roundAnyRawFNToRecFN.io.in := rawIn
    roundAnyRawFNToRecFN.io.roundingMode := io.roundingMode
    roundAnyRawFNToRecFN.io.detectTininess := io.detectTininess
    io.out := roundAnyRawFNToRecFN.io.out
    io.exceptionFlags := roundAnyRawFNToRecFN.io.exceptionFlags
  }
}
