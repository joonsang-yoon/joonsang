package HardUtils

import chisel3._
import chisel3.util._
import HardUtils.ConcatOrder._

object WallaceReducerCarrySave {
  def apply(
    inputColumns: Array[Seq[Bool]],
    counterUsage: CounterUsage,
    concatOrder:  Seq[Part] = ConcatOrder.Default
  ): Array[Seq[Bool]] = {
    val numColumns = inputColumns.size
    val outputColumns = Array.fill(numColumns)(Seq.empty[Bool])
    val carryColumns = Array.fill(numColumns + 1)(Seq.empty[Bool])

    for (i <- inputColumns.indices) {
      var colBits = inputColumns(i)

      val num3to2 = colBits.size / 3
      for (_ <- 0 until num3to2) {
        val (sum, carry) = Counter32(colBits(0), colBits(1), colBits(2))
        outputColumns(i) = outputColumns(i) :+ sum
        carryColumns(i + 1) = carryColumns(i + 1) :+ carry
        colBits = colBits.drop(3)
      }
      counterUsage.num3to2 += num3to2

      val num2to2 = colBits.size / 2
      for (_ <- 0 until num2to2) {
        val (sum, carry) = Counter22(colBits(0), colBits(1))
        outputColumns(i) = outputColumns(i) :+ sum
        carryColumns(i + 1) = carryColumns(i + 1) :+ carry
        colBits = colBits.drop(2)
      }
      counterUsage.num2to2 += num2to2

      val carryIns: Seq[Bool] = Seq.empty
      outputColumns(i) = ConcatOrder.assemble(
        colBits = colBits,
        carryIns = carryIns,
        carryCols = carryColumns(i),
        outCols = outputColumns(i),
        order = concatOrder
      )
    }

    outputColumns
  }
}

object DaddaReducerCarrySave {
  def apply(
    inputColumns: Array[Seq[Bool]],
    targetHeight: Int,
    counterUsage: CounterUsage,
    concatOrder:  Seq[Part] = ConcatOrder.Default
  ): Array[Seq[Bool]] = {
    val numColumns = inputColumns.size
    val outputColumns = Array.fill(numColumns)(Seq.empty[Bool])
    val carryColumns = Array.fill(numColumns + 1)(Seq.empty[Bool])

    for (i <- inputColumns.indices) {
      var colBits = inputColumns(i)

      val incomingHeight = colBits.size + carryColumns(i).size
      var excessHeight = incomingHeight - targetHeight

      if (excessHeight > 0) {
        val num3to2 = scala.math.min(colBits.size / 3, excessHeight / 2)
        for (_ <- 0 until num3to2) {
          val (sum, carry) = Counter32(colBits(0), colBits(1), colBits(2))
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          colBits = colBits.drop(3)
        }
        excessHeight -= 2 * num3to2
        counterUsage.num3to2 += num3to2

        val num2to2 = scala.math.min(colBits.size / 2, excessHeight)
        for (_ <- 0 until num2to2) {
          val (sum, carry) = Counter22(colBits(0), colBits(1))
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          colBits = colBits.drop(2)
        }
        excessHeight -= num2to2
        counterUsage.num2to2 += num2to2
      }

      val carryIns: Seq[Bool] = Seq.empty
      outputColumns(i) = ConcatOrder.assemble(
        colBits = colBits,
        carryIns = carryIns,
        carryCols = carryColumns(i),
        outCols = outputColumns(i),
        order = concatOrder
      )
    }

    outputColumns
  }
}
