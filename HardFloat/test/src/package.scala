package HardFloat

import chisel3._

package object test {
  object EquivRecFN {
    def apply(expWidth: Int, sigWidth: Int, a: UInt, b: UInt): Bool = {
      val top4A = a(expWidth + sigWidth, expWidth + sigWidth - 3)
      val top4B = b(expWidth + sigWidth, expWidth + sigWidth - 3)

      Mux(
        (top4A(2, 0) === 0.U) || (top4A(2, 0) === 7.U),
        (top4A === top4B) && (a(sigWidth - 2, 0) === b(sigWidth - 2, 0)),
        Mux(
          top4A(2, 0) === 6.U,
          top4A === top4B,
          a === b
        )
      )
    }
  }
}
