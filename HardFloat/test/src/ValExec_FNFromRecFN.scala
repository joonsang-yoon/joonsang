package HardFloat.test

import chisel3._
import HardFloat._

class ValExec_FNFromRecFN(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((expWidth + sigWidth).W))

    val out = Output(UInt((expWidth + sigWidth).W))

    val check = Output(Bool())
    val pass = Output(Bool())
  })

  io.out := FNFromRecFN(expWidth, sigWidth, RecFNFromFN(expWidth, sigWidth, io.a))

  io.check := true.B
  io.pass := io.out === io.a
}
