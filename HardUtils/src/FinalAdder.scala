package HardUtils

import chisel3._
import chisel3.util._

object FinalAdder {
  def apply(columns: Array[Seq[Bool]]): UInt = {
    val splitIndex = columns.indexWhere(_.size > 1) match {
      case -1 => columns.size
      case x  => x
    }
    val lower = Cat(columns.take(splitIndex).toSeq.reverse.map(_.lift(0).getOrElse(false.B)))
    val row1 = Cat(columns.drop(splitIndex).toSeq.reverse.map(_.lift(0).getOrElse(false.B)))
    val row2 = Cat(columns.drop(splitIndex).toSeq.reverse.map(_.lift(1).getOrElse(false.B)))
    val upper = row1 + row2
    if (splitIndex == 0) {
      upper
    } else {
      Cat(upper, lower)
    }
  }
}
