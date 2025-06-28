package HardUtils

import chisel3._

object ConcatOrder {
  sealed trait Part
  case object ColBits extends Part
  case object CarryIns extends Part
  case object CarryCols extends Part
  case object OutCols extends Part

  val Default: Seq[Part] = Seq(ColBits, CarryIns, CarryCols, OutCols)

  def assemble(
    colBits:   Seq[Bool],
    carryIns:  Seq[Bool],
    carryCols: Seq[Bool],
    outCols:   Seq[Bool],
    order:     Seq[Part]
  ): Seq[Bool] = {
    order.foldLeft(Seq.empty[Bool]) { (acc, p) =>
      acc ++ (p match {
        case ColBits   => colBits
        case CarryIns  => carryIns
        case CarryCols => carryCols
        case OutCols   => outCols
      })
    }
  }
}
