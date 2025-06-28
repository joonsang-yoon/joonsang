package HardFloat

import chisel3._
import chisel3.util._
import Consts._

class MulFullRawFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val a = Input(new RawFloat(expWidth, sigWidth))
    val b = Input(new RawFloat(expWidth, sigWidth))
    val invalidExc = Output(Bool())
    val rawOut = Output(new RawFloat(expWidth, 2 * sigWidth - 1))
  })

  val notSigNaN_invalidExc = (io.a.isInf && io.b.isZero) || (io.a.isZero && io.b.isInf)
  val notNaN_isInfOut = io.a.isInf || io.b.isInf
  val notNaN_isZeroOut = io.a.isZero || io.b.isZero
  val notNaN_signOut = io.a.sign ^ io.b.sign
  val common_sExpOut = ((io.a.sExp(expWidth, 0) +& io.b.sExp(expWidth, 0)) - (BigInt(1) << expWidth).U).asSInt
  val common_sigOut = io.a.sig(sigWidth - 1, 0) * io.b.sig(sigWidth - 1, 0)

  io.invalidExc := IsSigNaNRawFloat(io.a) || IsSigNaNRawFloat(io.b) || notSigNaN_invalidExc
  io.rawOut.isNaN := io.a.isNaN || io.b.isNaN
  io.rawOut.isInf := notNaN_isInfOut
  io.rawOut.isZero := notNaN_isZeroOut
  io.rawOut.sign := notNaN_signOut
  io.rawOut.sExp := common_sExpOut
  io.rawOut.sig := common_sigOut
}

class MulRawFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val a = Input(new RawFloat(expWidth, sigWidth))
    val b = Input(new RawFloat(expWidth, sigWidth))
    val invalidExc = Output(Bool())
    val rawOut = Output(new RawFloat(expWidth, sigWidth + 2))
  })

  val mulFullRaw = Module(new MulFullRawFN(expWidth, sigWidth))

  mulFullRaw.io.a := io.a
  mulFullRaw.io.b := io.b

  io.invalidExc := mulFullRaw.io.invalidExc
  io.rawOut := mulFullRaw.io.rawOut
  io.rawOut.sig := {
    val sig = mulFullRaw.io.rawOut.sig
    Cat(sig(2 * sigWidth - 1, sigWidth - 2), sig(sigWidth - 3, 0).orR)
  }
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
