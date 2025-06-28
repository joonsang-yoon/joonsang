package HardUtils

import chisel3._
import chisel3.util._
import HardUtils.ConcatOrder._

object WallaceReducerCarryChain {
  def apply(
    inputColumns: Array[Seq[Bool]],
    counterUsage: CounterUsage,
    concatOrder:  Seq[Part] = ConcatOrder.Default
  ): Array[Seq[Bool]] = {
    if (inputColumns.map(_.size).max <= 4) {
      WallaceReducerCarrySave(inputColumns, counterUsage, concatOrder)
    } else {
      val numColumns = inputColumns.size
      val outputColumns = Array.fill(numColumns)(Seq.empty[Bool])
      val carryColumns = Array.fill(numColumns + 1)(Seq.empty[Bool])
      val carryOutColumns = Array.fill(numColumns + 1)(Seq.empty[Bool])

      for (i <- inputColumns.indices) {
        var colBits = inputColumns(i)
        var carryIns = carryOutColumns(i)

        def can53Cin = carryIns.nonEmpty && (colBits.size >= 4)
        def can43Cin = carryIns.nonEmpty && (colBits.size >= 3)
        def can32Cin = carryIns.nonEmpty && (colBits.size >= 2)
        def can22Cin = carryIns.nonEmpty && (colBits.size >= 1)

        def can53 = colBits.size >= 5
        def can43 = colBits.size >= 4
        def can32 = colBits.size >= 3
        def can22 = colBits.size >= 2

        def use53Cin(): Unit = {
          val (sum, carry, carryOut) = Counter53Cin(colBits(0), colBits(1), colBits(2), colBits(3), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(4)
          carryIns = carryIns.tail
          counterUsage.num5to3 += 1
        }
        def use43Cin(): Unit = {
          val (sum, carry, carryOut) = Counter43Cin(colBits(0), colBits(1), colBits(2), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(3)
          carryIns = carryIns.tail
          counterUsage.num4to3 += 1
        }
        def use32Cin(): Unit = {
          val (sum, carry) = Counter32Cin(colBits(0), colBits(1), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          colBits = colBits.drop(2)
          carryIns = carryIns.tail
          counterUsage.num3to2 += 1
        }
        def use22Cin(): Unit = {
          val (sum, carry) = Counter22Cin(colBits(0), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          colBits = colBits.drop(1)
          carryIns = carryIns.tail
          counterUsage.num2to2 += 1
        }

        def use53(): Unit = {
          val (sum, carry, carryOut) = Counter53(colBits(0), colBits(1), colBits(2), colBits(3), colBits(4))
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(5)
          counterUsage.num5to3 += 1
        }
        def use43(): Unit = {
          val (sum, carry, carryOut) = Counter43(colBits(0), colBits(1), colBits(2), colBits(3))
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(4)
          counterUsage.num4to3 += 1
        }
        def use32(): Unit = {
          val (sum, carryOut) = Counter32(colBits(0), colBits(1), colBits(2))
          outputColumns(i) = outputColumns(i) :+ sum
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(3)
          counterUsage.num3to2 += 1
        }
        def use22(): Unit = {
          val (sum, carryOut) = Counter22(colBits(0), colBits(1))
          outputColumns(i) = outputColumns(i) :+ sum
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(2)
          counterUsage.num2to2 += 1
        }

        var progress = true
        while (progress) {
          progress = false

          if (can53Cin) { use53Cin(); progress = true }
          else if (can43Cin) { use43Cin(); progress = true }
          else if (can32Cin) { use32Cin(); progress = true }
          else if (can22Cin) { use22Cin(); progress = true }
          else if (can53) { use53(); progress = true }
          else if (can43) { use43(); progress = true }
          else if (can32) { use32(); progress = true }
          else if (can22) { use22(); progress = true }
        }

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
}

object DaddaReducerCarryChain {
  def apply(
    inputColumns: Array[Seq[Bool]],
    targetHeight: Int,
    counterUsage: CounterUsage,
    concatOrder:  Seq[Part] = ConcatOrder.Default
  ): Array[Seq[Bool]] = {
    if (inputColumns.map(_.size).max <= 4) {
      DaddaReducerCarrySave(inputColumns, targetHeight, counterUsage, concatOrder)
    } else {
      val numColumns = inputColumns.size
      val outputColumns = Array.fill(numColumns)(Seq.empty[Bool])
      val carryColumns = Array.fill(numColumns + 1)(Seq.empty[Bool])
      val carryOutColumns = Array.fill(numColumns + 1)(Seq.empty[Bool])

      for (i <- inputColumns.indices) {
        var colBits = inputColumns(i)
        var carryIns = carryOutColumns(i)

        val incomingHeight = colBits.size + carryColumns(i).size + carryIns.size
        var excessHeight = incomingHeight - targetHeight

        def can53Cin = carryIns.nonEmpty && (colBits.size >= 4)
        def can43Cin = carryIns.nonEmpty && (colBits.size >= 3)
        def can32Cin = carryIns.nonEmpty && (colBits.size >= 2)
        def can22Cin = carryIns.nonEmpty && (colBits.size >= 1)

        def can53 = colBits.size >= 5
        def can43 = colBits.size >= 4
        def can32 = colBits.size >= 3
        def can22 = colBits.size >= 2

        def use53Cin(): Unit = {
          val (sum, carry, carryOut) = Counter53Cin(colBits(0), colBits(1), colBits(2), colBits(3), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(4)
          carryIns = carryIns.tail
          excessHeight -= 4
          counterUsage.num5to3 += 1
        }
        def use43Cin(): Unit = {
          val (sum, carry, carryOut) = Counter43Cin(colBits(0), colBits(1), colBits(2), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(3)
          carryIns = carryIns.tail
          excessHeight -= 3
          counterUsage.num4to3 += 1
        }
        def use32Cin(): Unit = {
          val (sum, carry) = Counter32Cin(colBits(0), colBits(1), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          colBits = colBits.drop(2)
          carryIns = carryIns.tail
          excessHeight -= 2
          counterUsage.num3to2 += 1
        }
        def use22Cin(): Unit = {
          val (sum, carry) = Counter22Cin(colBits(0), carryIns.head)
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          colBits = colBits.drop(1)
          carryIns = carryIns.tail
          excessHeight -= 1
          counterUsage.num2to2 += 1
        }

        def use53(): Unit = {
          val (sum, carry, carryOut) = Counter53(colBits(0), colBits(1), colBits(2), colBits(3), colBits(4))
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(5)
          excessHeight -= 4
          counterUsage.num5to3 += 1
        }
        def use43(): Unit = {
          val (sum, carry, carryOut) = Counter43(colBits(0), colBits(1), colBits(2), colBits(3))
          outputColumns(i) = outputColumns(i) :+ sum
          carryColumns(i + 1) = carryColumns(i + 1) :+ carry
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(4)
          excessHeight -= 3
          counterUsage.num4to3 += 1
        }
        def use32(): Unit = {
          val (sum, carryOut) = Counter32(colBits(0), colBits(1), colBits(2))
          outputColumns(i) = outputColumns(i) :+ sum
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(3)
          excessHeight -= 2
          counterUsage.num3to2 += 1
        }
        def use22(): Unit = {
          val (sum, carryOut) = Counter22(colBits(0), colBits(1))
          outputColumns(i) = outputColumns(i) :+ sum
          carryOutColumns(i + 1) = carryOutColumns(i + 1) :+ carryOut
          colBits = colBits.drop(2)
          excessHeight -= 1
          counterUsage.num2to2 += 1
        }

        var progress = true
        while ((excessHeight > 0) && progress) {
          progress = false

          if ((excessHeight >= 4) && can53Cin) { use53Cin(); progress = true }
          else if ((excessHeight >= 3) && can43Cin) { use43Cin(); progress = true }
          else if ((excessHeight >= 2) && can32Cin) { use32Cin(); progress = true }
          else if ((excessHeight >= 1) && can22Cin) { use22Cin(); progress = true }
          else if ((excessHeight >= 4) && can53) { use53(); progress = true }
          else if ((excessHeight >= 3) && can43) { use43(); progress = true }
          else if ((excessHeight >= 2) && can32) { use32(); progress = true }
          else if ((excessHeight >= 1) && can22) { use22(); progress = true }
        }

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
}
