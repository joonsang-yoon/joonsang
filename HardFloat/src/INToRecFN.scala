package HardFloat

import chisel3._
import Consts._

class INToRecFN(intWidth: Int, expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val signedIn = Input(Bool())
    val in = Input(UInt(intWidth.W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val intAsRawFloat = RawFloatFromIN(io.signedIn, io.in)

  val roundAnyRawFNToRecFN = Module(
    new RoundAnyRawFNToRecFN(
      intAsRawFloat.expWidth,
      intWidth,
      expWidth,
      sigWidth,
      flRoundOpt_sigMSBitAlwaysZero | flRoundOpt_neverUnderflows
    )
  )
  roundAnyRawFNToRecFN.io.invalidExc := false.B
  roundAnyRawFNToRecFN.io.infiniteExc := false.B
  roundAnyRawFNToRecFN.io.in := intAsRawFloat
  roundAnyRawFNToRecFN.io.roundingMode := io.roundingMode
  roundAnyRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out := roundAnyRawFNToRecFN.io.out
  io.exceptionFlags := roundAnyRawFNToRecFN.io.exceptionFlags
}
