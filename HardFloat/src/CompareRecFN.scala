package HardFloat

import chisel3._
import chisel3.util._

class CompareRecFN(expWidth: Int, sigWidth: Int) extends RawModule {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth + 1).W))
    val b = Input(UInt((expWidth + sigWidth + 1).W))
    val signaling = Input(Bool())
    val lt = Output(Bool())
    val eq = Output(Bool())
    val gt = Output(Bool())
    val exceptionFlags = Output(UInt(5.W))
  })

  val rawA = RawFloatFromRecFN(expWidth, sigWidth, io.a)
  val rawB = RawFloatFromRecFN(expWidth, sigWidth, io.b)

  val ordered = !rawA.isNaN && !rawB.isNaN
  val bothInfs = rawA.isInf && rawB.isInf
  val bothZeros = rawA.isZero && rawB.isZero
  val eqExps = rawA.sExp(expWidth, 0) === rawB.sExp(expWidth, 0)
  val common_ltMags = (rawA.sExp(expWidth, 0) < rawB.sExp(expWidth, 0)) ||
    (eqExps && (rawA.sig(sigWidth - 2, 0) < rawB.sig(sigWidth - 2, 0)))
  val common_eqMags = eqExps && (rawA.sig(sigWidth - 2, 0) === rawB.sig(sigWidth - 2, 0))

  val ordered_lt = !bothZeros && (
    (rawA.sign && !rawB.sign) ||
      (!bothInfs && (
        (rawA.sign && !common_ltMags && !common_eqMags) ||
          (!rawB.sign && common_ltMags)
      ))
  )
  val ordered_eq = bothZeros || ((rawA.sign === rawB.sign) && (bothInfs || common_eqMags))

  val invalid = IsSigNaNRawFloat(rawA) || IsSigNaNRawFloat(rawB) || (io.signaling && !ordered)

  io.lt := ordered && ordered_lt
  io.eq := ordered && ordered_eq
  io.gt := ordered && !ordered_lt && !ordered_eq
  io.exceptionFlags := Cat(invalid, 0.U(4.W))
}
