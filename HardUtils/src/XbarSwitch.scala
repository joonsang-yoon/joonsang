package HardUtils

import chisel3._
import chisel3.util._

/**
 * Represents a request bundle for the crossbar switch.
 * It contains the destination port index and the payload data to be routed.
 *
 * @tparam T The Chisel data type of the payload.
 * @param gen An instance of the payload data type used as a hardware template.
 * @param nOut The total number of output ports in the crossbar switch, used to size the destination field.
 */
class XbarSwitchReq[T <: Data](private val gen: T, val nOut: Int) extends Bundle {
  val dest = UInt(log2Ceil(nOut).W)
  val data = gen.cloneType
}

/**
 * The input/output interface for the [[XbarSwitch]] module.
 *
 * @tparam T The Chisel data type of the payload routed through the switch.
 * @param gen An instance of the payload data type used as a hardware template.
 * @param nIn The number of input ports.
 * @param nOut The number of output ports.
 */
class XbarSwitchIO[T <: Data](private val gen: T, val nIn: Int, val nOut: Int) extends Bundle {
  val in = Flipped(Vec(nIn, Decoupled(new XbarSwitchReq(gen, nOut))))
  val out = Vec(nOut, Decoupled(gen))
  val chosen = Output(Vec(nOut, UInt(log2Ceil(nIn).W)))
}

/**
 * A generic unicast crossbar switch module.
 *
 * Routes decoupled requests from `nIn` input ports to `nOut` output ports based on the `dest`
 * field in the incoming request. Arbitration at each output port is handled by instantiating
 * arbiters via the provided `mkArbiter` factory function.
 *
 * @tparam T The Chisel data type of the payload routed through the switch.
 * @param gen An instance of the payload data type used as a hardware template.
 * @param nIn The number of input ports (must be strictly greater than 0).
 * @param nOut The number of output ports (must be strictly greater than 0).
 * @param mkArbiter A factory function that takes an output port index and returns an
 *                  [[ArbiterIO]] instance used to arbitrate requests for that specific output port.
 */
class XbarSwitch[T <: Data](
  private val gen: T,
  val nIn:         Int,
  val nOut:        Int,
  mkArbiter:       Int => ArbiterIO[T]
) extends Module {
  require(nIn > 0, "XbarSwitch: nIn must be > 0")
  require(nOut > 0, "XbarSwitch: nOut must be > 0")

  val io = IO(new XbarSwitchIO(gen, nIn, nOut))

  private val outputArbiters: IndexedSeq[ArbiterIO[T]] = IndexedSeq.tabulate(nOut) { outputIdx =>
    mkArbiter(outputIdx)
  }

  // 1. Forward Path
  for (outputIdx <- 0 until nOut) {
    val arbiter = outputArbiters(outputIdx)

    for (inputIdx <- 0 until nIn) {
      arbiter.in(inputIdx).bits := io.in(inputIdx).bits.data
      arbiter.in(inputIdx).valid :=
        io.in(inputIdx).valid && (io.in(inputIdx).bits.dest === outputIdx.U(log2Ceil(nOut).W))
    }

    io.out(outputIdx) <> arbiter.out
    io.chosen(outputIdx) := arbiter.chosen
  }

  // 2. Backward Path
  for (inputIdx <- 0 until nIn) {
    io.in(inputIdx).ready := VecInit(outputArbiters.map(_.in(inputIdx).ready))(io.in(inputIdx).bits.dest)

    when(io.in(inputIdx).valid) {
      assert(io.in(inputIdx).bits.dest < nOut.U)
    }
  }
}

class MyCustomArbiter[T <: Data](
  gen:         T,
  n:           Int,
  arbiterName: String
) extends Arbiter(gen, n) {
  override def desiredName: String = arbiterName
}

class MyCustomRRArbiter[T <: Data](
  gen:           T,
  n:             Int,
  initLastGrant: Boolean,
  arbiterName:   String
) extends RRArbiter(gen, n, initLastGrant) {
  override def desiredName: String = arbiterName
}

class MyCustomLockingArbiter[T <: Data](
  gen:         T,
  n:           Int,
  count:       Int,
  needsLock:   Option[T => Bool],
  arbiterName: String
) extends LockingArbiter(gen, n, count, needsLock) {
  override def desiredName: String = arbiterName
}

class MyCustomLockingRRArbiter[T <: Data](
  gen:           T,
  n:             Int,
  count:         Int,
  needsLock:     Option[T => Bool],
  initLastGrant: Boolean,
  arbiterName:   String
) extends LockingRRArbiter(gen, n, count, needsLock, initLastGrant) {
  override def desiredName: String = arbiterName
}

class FixedPriorityArbiterExample extends Module {
  private val width = 8
  private val n = 4

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(new MyCustomArbiter(UInt(width.W), n, "FixedPriorityArbiter"))

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class RoundRobinArbiterInitFalseExample extends Module {
  private val width = 8
  private val n = 4
  private val initLastGrant = false

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(new MyCustomRRArbiter(UInt(width.W), n, initLastGrant, "RoundRobinArbiterInitFalse"))

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class RoundRobinArbiterInitTrueExample extends Module {
  private val width = 8
  private val n = 4
  private val initLastGrant = true

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(new MyCustomRRArbiter(UInt(width.W), n, initLastGrant, "RoundRobinArbiterInitTrue"))

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class UnconditionalLockingArbiterExample extends Module {
  private val width = 8
  private val n = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = None

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(new MyCustomLockingArbiter(UInt(width.W), n, count, needsLock, "UnconditionalLockingArbiter"))

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class ConditionalLockingArbiterExample extends Module {
  private val width = 8
  private val n = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = Some((d: UInt) => d(0))

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(new MyCustomLockingArbiter(UInt(width.W), n, count, needsLock, "ConditionalLockingArbiter"))

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class UnconditionalLockingRRArbiterInitFalseExample extends Module {
  private val width = 8
  private val n = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = None
  private val initLastGrant = false

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(
    new MyCustomLockingRRArbiter(
      UInt(width.W),
      n,
      count,
      needsLock,
      initLastGrant,
      "UnconditionalLockingRRArbiterInitFalse"
    )
  )

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class UnconditionalLockingRRArbiterInitTrueExample extends Module {
  private val width = 8
  private val n = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = None
  private val initLastGrant = true

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(
    new MyCustomLockingRRArbiter(
      UInt(width.W),
      n,
      count,
      needsLock,
      initLastGrant,
      "UnconditionalLockingRRArbiterInitTrue"
    )
  )

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class ConditionalLockingRRArbiterInitFalseExample extends Module {
  private val width = 8
  private val n = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = Some((d: UInt) => d(0))
  private val initLastGrant = false

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(
    new MyCustomLockingRRArbiter(
      UInt(width.W),
      n,
      count,
      needsLock,
      initLastGrant,
      "ConditionalLockingRRArbiterInitFalse"
    )
  )

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class ConditionalLockingRRArbiterInitTrueExample extends Module {
  private val width = 8
  private val n = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = Some((d: UInt) => d(0))
  private val initLastGrant = true

  val io = IO(new ArbiterIO(UInt(8.W), n))

  val arb = Module(
    new MyCustomLockingRRArbiter(
      UInt(width.W),
      n,
      count,
      needsLock,
      initLastGrant,
      "ConditionalLockingRRArbiterInitTrue"
    )
  )

  for (i <- 0 until n) arb.io.in(i) <> io.in(i)
  io.out <> arb.io.out
  io.chosen := arb.io.chosen
}

class MyCustomXbarSwitch[T <: Data](
  gen:            T,
  nIn:            Int,
  nOut:           Int,
  xbarSwitchName: String
) extends XbarSwitch[T](
      gen,
      nIn,
      nOut,
      outputIdx => {
        val arb = Module(new Arbiter(gen, nIn))
        arb.suggestName(s"${outputIdx}")
        arb.io
      }
    ) {
  override def desiredName: String = xbarSwitchName
}

class MyCustomRRXbarSwitch[T <: Data](
  gen:            T,
  nIn:            Int,
  nOut:           Int,
  initLastGrant:  Boolean,
  xbarSwitchName: String
) extends XbarSwitch[T](
      gen,
      nIn,
      nOut,
      outputIdx => {
        val arb = Module(new RRArbiter(gen, nIn, initLastGrant))
        arb.suggestName(s"${outputIdx}")
        arb.io
      }
    ) {
  override def desiredName: String = xbarSwitchName
}

class MyCustomLockingXbarSwitch[T <: Data](
  gen:            T,
  nIn:            Int,
  nOut:           Int,
  count:          Int,
  needsLock:      Option[T => Bool],
  xbarSwitchName: String
) extends XbarSwitch[T](
      gen,
      nIn,
      nOut,
      outputIdx => {
        val arb = Module(new LockingArbiter(gen, nIn, count, needsLock))
        arb.suggestName(s"${outputIdx}")
        arb.io
      }
    ) {
  override def desiredName: String = xbarSwitchName
}

class MyCustomLockingRRXbarSwitch[T <: Data](
  gen:            T,
  nIn:            Int,
  nOut:           Int,
  count:          Int,
  needsLock:      Option[T => Bool],
  initLastGrant:  Boolean,
  xbarSwitchName: String
) extends XbarSwitch[T](
      gen,
      nIn,
      nOut,
      outputIdx => {
        val arb = Module(new LockingRRArbiter(gen, nIn, count, needsLock, initLastGrant))
        arb.suggestName(s"${outputIdx}")
        arb.io
      }
    ) {
  override def desiredName: String = xbarSwitchName
}

class FixedPriorityXbarSwitchExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(new MyCustomXbarSwitch(UInt(width.W), nIn, nOut, "FixedPriorityXbarSwitch"))

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class RoundRobinXbarSwitchInitFalseExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val initLastGrant = false

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomRRXbarSwitch(UInt(width.W), nIn, nOut, initLastGrant, "RoundRobinXbarSwitchInitFalse")
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class RoundRobinXbarSwitchInitTrueExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val initLastGrant = true

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomRRXbarSwitch(UInt(width.W), nIn, nOut, initLastGrant, "RoundRobinXbarSwitchInitTrue")
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class UnconditionalLockingXbarSwitchExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = None

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomLockingXbarSwitch(UInt(width.W), nIn, nOut, count, needsLock, "UnconditionalLockingXbarSwitch")
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class ConditionalLockingXbarSwitchExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = Some((d: UInt) => d(0))

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomLockingXbarSwitch(UInt(width.W), nIn, nOut, count, needsLock, "ConditionalLockingXbarSwitch")
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class UnconditionalLockingRRXbarSwitchInitFalseExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = None
  private val initLastGrant = false

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomLockingRRXbarSwitch(
      UInt(width.W),
      nIn,
      nOut,
      count,
      needsLock,
      initLastGrant,
      "UnconditionalLockingRRXbarSwitchInitFalse"
    )
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class UnconditionalLockingRRXbarSwitchInitTrueExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = None
  private val initLastGrant = true

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomLockingRRXbarSwitch(
      UInt(width.W),
      nIn,
      nOut,
      count,
      needsLock,
      initLastGrant,
      "UnconditionalLockingRRXbarSwitchInitTrue"
    )
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class ConditionalLockingRRXbarSwitchInitFalseExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = Some((d: UInt) => d(0))
  private val initLastGrant = false

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomLockingRRXbarSwitch(
      UInt(width.W),
      nIn,
      nOut,
      count,
      needsLock,
      initLastGrant,
      "ConditionalLockingRRXbarSwitchInitFalse"
    )
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}

class ConditionalLockingRRXbarSwitchInitTrueExample extends Module {
  private val width = 8
  private val nIn = 4
  private val nOut = 4
  private val count = 6
  private val needsLock: Option[UInt => Bool] = Some((d: UInt) => d(0))
  private val initLastGrant = true

  val io = IO(new XbarSwitchIO(UInt(width.W), nIn, nOut))

  val xbarSwitch = Module(
    new MyCustomLockingRRXbarSwitch(
      UInt(width.W),
      nIn,
      nOut,
      count,
      needsLock,
      initLastGrant,
      "ConditionalLockingRRXbarSwitchInitTrue"
    )
  )

  for (i <- 0 until nIn) xbarSwitch.io.in(i) <> io.in(i)
  for (o <- 0 until nOut) io.out(o) <> xbarSwitch.io.out(o)
  io.chosen := xbarSwitch.io.chosen
}
