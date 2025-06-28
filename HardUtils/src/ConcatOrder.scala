package HardUtils

import chisel3._

object ConcatOrder {
  sealed trait Part
  case object OutCols extends Part
  case object CarryCols extends Part
  case object ColBits extends Part
  case object CarryIns extends Part

  val Default: Seq[Part] = Seq(OutCols, CarryCols, ColBits, CarryIns)

  def assemble(
    outCols:   Seq[Bool],
    carryCols: Seq[Bool],
    colBits:   Seq[Bool],
    carryIns:  Seq[Bool],
    order:     Seq[Part]
  ): Seq[Bool] = {
    order.foldLeft(Seq.empty[Bool]) { (acc, p) =>
      acc ++ (p match {
        case OutCols   => outCols
        case CarryCols => carryCols
        case ColBits   => colBits
        case CarryIns  => carryIns
      })
    }
  }
}
