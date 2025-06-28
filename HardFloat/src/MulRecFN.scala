package HardFloat

import chisel3._
import chisel3.util._

class MulRawFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val a = Input(new RawFloat(expWidth, sigWidth))
    val b = Input(new RawFloat(expWidth, sigWidth))
    val invalidExc = Output(Bool())
    val rawOut = Output(new RawFloat(expWidth, sigWidth + 2))
  })

  val notNaN_isInfOut = io.a.isInf || io.b.isInf
  val notNaN_isZeroOut = io.a.isZero || io.b.isZero
  val notNaN_signOut = io.a.sign ^ io.b.sign
  val bSExpMinusOffset = Cat(~io.b.sExp(expWidth), io.b.sExp(expWidth - 1, 0)).asSInt
  val common_sExpOut = io.a.sExp(expWidth, 0).pad(expWidth + 2).asSInt + bSExpMinusOffset
  val common_sigOut = io.a.sig(sigWidth - 1, 0) * io.b.sig(sigWidth - 1, 0)

  io.invalidExc := IsSigNaNRawFloat(io.a) || IsSigNaNRawFloat(io.b) ||
    (io.a.isInf && io.b.isZero) || (io.a.isZero && io.b.isInf)
  io.rawOut.isNaN := io.a.isNaN || io.b.isNaN
  io.rawOut.isInf := notNaN_isInfOut
  io.rawOut.isZero := notNaN_isZeroOut
  io.rawOut.sign := notNaN_signOut
  io.rawOut.sExp := common_sExpOut
  io.rawOut.sig := Cat(common_sigOut(2 * sigWidth - 1, sigWidth - 2), common_sigOut(sigWidth - 3, 0).orR)
}

class MulRecFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth + 1).W))
    val b = Input(UInt((expWidth + sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(Bool())
    val out = Output(UInt((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(UInt(5.W))
  })

  val mulRawFN = Module(new MulRawFN(expWidth, sigWidth))

  mulRawFN.io.a := RawFloatFromRecFN(expWidth, sigWidth, io.a)
  mulRawFN.io.b := RawFloatFromRecFN(expWidth, sigWidth, io.b)

  val roundRawFNToRecFN = Module(new RoundRawFNToRecFN(expWidth, sigWidth, 0))
  roundRawFNToRecFN.io.invalidExc := mulRawFN.io.invalidExc
  roundRawFNToRecFN.io.infiniteExc := false.B
  roundRawFNToRecFN.io.in := mulRawFN.io.rawOut
  roundRawFNToRecFN.io.roundingMode := io.roundingMode
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
