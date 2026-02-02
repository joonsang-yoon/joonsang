package HardInt

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.constants.ScalarOpConstants
import HardUtils._

object ALU {
  val SZ_ALU_FN = 4

  def FN_X = BitPat("b????")
  def FN_ADD = 0.U
  def FN_SL = 1.U
  def FN_SEQ = 2.U
  def FN_SNE = 3.U
  def FN_XOR = 4.U
  def FN_SR = 5.U
  def FN_OR = 6.U
  def FN_AND = 7.U
  def FN_SUB = 10.U
  def FN_SRA = 11.U
  def FN_SLT = 12.U
  def FN_SGE = 13.U
  def FN_SLTU = 14.U
  def FN_SGEU = 15.U

  def FN_MUL = FN_ADD
  def FN_MULH = FN_SL
  def FN_MULHSU = FN_SEQ
  def FN_MULHU = FN_SNE

  def FN_DIV = FN_XOR
  def FN_DIVU = FN_SR
  def FN_REM = FN_OR
  def FN_REMU = FN_AND

  def isSub(fn:       UInt) = fn(3)
  def isCmp(fn:       UInt) = fn(3) && fn(2)
  def cmpUnsigned(fn: UInt) = fn(1)
  def cmpInverted(fn: UInt) = fn(0)
  def cmpEq(fn:       UInt) = !fn(3)
}

import ALU._

class ALU(dataWidth: Int) extends RawModule with ScalarOpConstants {
  val io = IO(new Bundle {
    val fn = Input(UInt(SZ_ALU_FN.W))
    val dw = Input(UInt(SZ_DW.W))
    val in1 = Input(UInt(dataWidth.W))
    val in2 = Input(UInt(dataWidth.W))
    val adder_out = Output(UInt(dataWidth.W))
    val cmp_out = Output(Bool())
    val out = Output(UInt(dataWidth.W))
  })

  val halfWidth = dataWidth >> 1

  // ADD, SUB
  val in2_inv = io.in2 ^ Replicate(dataWidth, isSub(io.fn))
  val in1_xor_in2 = io.in1 ^ in2_inv
  val in1_and_in2 = io.in1 & in2_inv
  io.adder_out := io.in1 + in2_inv + isSub(io.fn)

  // SLT, SLTU
  val slt = Mux(
    io.in1(dataWidth - 1) === io.in2(dataWidth - 1),
    io.adder_out(dataWidth - 1),
    Mux(
      cmpUnsigned(io.fn),
      io.in2(dataWidth - 1),
      io.in1(dataWidth - 1)
    )
  )
  io.cmp_out := cmpInverted(io.fn) ^ Mux(cmpEq(io.fn), in1_xor_in2 === 0.U(dataWidth.W), slt)

  // AND, OR, XOR
  val logic = Mux((io.fn === FN_XOR) || (io.fn === FN_OR), in1_xor_in2, 0.U(dataWidth.W)) |
    Mux((io.fn === FN_OR) || (io.fn === FN_AND), in1_and_in2, 0.U(dataWidth.W))

  // SLL, SRL, SRA
  val (shamt, shin_r) = if (dataWidth == 32) {
    (io.in2(4, 0), io.in1)
  } else {
    require(dataWidth == 64)
    val shin_hi_32 = Replicate(32, isSub(io.fn) && io.in1(31))
    val shin_hi = Mux(io.dw === DW_64, io.in1(63, 32), shin_hi_32)
    val shamt = Cat(io.in2(5) && (io.dw === DW_64), io.in2(4, 0))
    (shamt, Cat(shin_hi, io.in1(31, 0)))
  }
  val shin = Mux((io.fn === FN_SR) || (io.fn === FN_SRA), shin_r, Reverse(shin_r))
  val shout_r = (Cat(isSub(io.fn) && shin(dataWidth - 1), shin).asSInt >> shamt)(dataWidth - 1, 0)
  val shout_l = Reverse(shout_r)
  val shout = Mux((io.fn === FN_SR) || (io.fn === FN_SRA), shout_r, 0.U(dataWidth.W)) |
    Mux(io.fn === FN_SL, shout_l, 0.U(dataWidth.W))

  val shift_logic = (isCmp(io.fn) && slt) | logic | shout

  val out = Mux((io.fn === FN_ADD) || (io.fn === FN_SUB), io.adder_out, shift_logic)

  val finalOut = Cat(
    Mux(
      (dataWidth > 32).B && (io.dw === DW_32),
      Replicate(halfWidth, out(halfWidth - 1)),
      out(dataWidth - 1, halfWidth)
    ),
    out(halfWidth - 1, 0)
  )

  io.out := finalOut
}
