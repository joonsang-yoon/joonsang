package HardFloat

import chisel3._

class DivSqrtRecF64 extends Module {
  val io = IO(new Bundle {
    val inReady_div = Output(Bool())
    val inReady_sqrt = Output(Bool())
    val inValid = Input(Bool())
    val sqrtOp = Input(Bool())
    val a = Input(Bits(65.W))
    val b = Input(Bits(65.W))
    val roundingMode = Input(Bits(3.W))
    val detectTininess = Input(UInt(1.W))
    val outValid_div = Output(Bool())
    val outValid_sqrt = Output(Bool())
    val out = Output(Bits(65.W))
    val exceptionFlags = Output(Bits(5.W))
  })

  val ds = Module(new DivSqrtRecF64_mulAddZ31(0))

  io.inReady_div := ds.io.inReady_div
  io.inReady_sqrt := ds.io.inReady_sqrt
  ds.io.inValid := io.inValid
  ds.io.sqrtOp := io.sqrtOp
  ds.io.a := io.a
  ds.io.b := io.b
  ds.io.roundingMode := io.roundingMode
  ds.io.detectTininess := io.detectTininess
  io.outValid_div := ds.io.outValid_div
  io.outValid_sqrt := ds.io.outValid_sqrt
  io.out := ds.io.out
  io.exceptionFlags := ds.io.exceptionFlags

  val mul = Module(new Mul54)

  mul.io.val_s0 := ds.io.usingMulAdd(0)
  mul.io.latch_a_s0 := ds.io.latchMulAddA_0
  mul.io.a_s0 := ds.io.mulAddA_0
  mul.io.latch_b_s0 := ds.io.latchMulAddB_0
  mul.io.b_s0 := ds.io.mulAddB_0
  mul.io.c_s2 := ds.io.mulAddC_2
  ds.io.mulAddResult_3 := mul.io.result_s3
}

class Mul54 extends Module {
  val io = IO(new Bundle {
    val val_s0 = Input(Bool())
    val latch_a_s0 = Input(Bool())
    val a_s0 = Input(UInt(54.W))
    val latch_b_s0 = Input(Bool())
    val b_s0 = Input(UInt(54.W))
    val c_s2 = Input(UInt(105.W))
    val result_s3 = Output(UInt(105.W))
  })

  val val_s1 = Reg(Bool())
  val val_s2 = Reg(Bool())
  val reg_a_s1 = Reg(UInt(54.W))
  val reg_b_s1 = Reg(UInt(54.W))
  val reg_a_s2 = Reg(UInt(54.W))
  val reg_b_s2 = Reg(UInt(54.W))
  val reg_result_s3 = Reg(UInt(105.W))

  val_s1 := io.val_s0
  val_s2 := val_s1

  when(io.val_s0) {
    when(io.latch_a_s0) {
      reg_a_s1 := io.a_s0
    }
    when(io.latch_b_s0) {
      reg_b_s1 := io.b_s0
    }
  }

  when(val_s1) {
    reg_a_s2 := reg_a_s1
    reg_b_s2 := reg_b_s1
  }

  when(val_s2) {
    reg_result_s3 := (reg_a_s2 * reg_b_s2)(104, 0) + io.c_s2
  }

  io.result_s3 := reg_result_s3
}
