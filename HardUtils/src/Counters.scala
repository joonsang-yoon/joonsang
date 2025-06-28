package HardUtils

import chisel3._
import chisel3.util._

case class CounterUsage(
  var num2to2: Int = 0,
  var num3to2: Int = 0,
  var num4to3: Int = 0,
  var num5to3: Int = 0
)

object Counter22 {
  // 2:2 counter:
  // a, b are at column i
  //
  // Returns (sum, carryOut):
  //   CarrySave:
  //     - sum   -> next stage, column i
  //     - carry -> next stage, column i+1
  //   CarryChain:
  //     - sum      -> next stage, column i
  //     - carryOut -> same stage, column i+1; feed as carryIn of column i+1
  // Reduces column height by 1.
  def apply(a: Bool, b: Bool): (Bool, Bool) = {
    val sum = a ^ b
    val carryOut = a & b
    (sum, carryOut)
  }
}

object Counter32 {
  // 3:2 counter:
  // a, b, c are at column i
  //
  // Returns (sum, carryOut):
  //   CarrySave:
  //     - sum   -> next stage, column i
  //     - carry -> next stage, column i+1
  //   CarryChain:
  //     - sum      -> next stage, column i
  //     - carryOut -> same stage, column i+1; feed as carryIn of column i+1
  // Reduces column height by 2.
  def apply(a: Bool, b: Bool, c: Bool): (Bool, Bool) = {
    val sum = a ^ b ^ c
    val carryOut = (a & b) | (b & c) | (c & a)
    (sum, carryOut)
  }
}

object Counter43 {
  // 4:3 counter:
  // a, b, c, d are at column i
  //
  // Returns (sum, carry, carryOut):
  //   - sum      -> next stage, column i
  //   - carry    -> next stage, column i+1
  //   - carryOut -> same stage, column i+1 (feed this as carryIn of column i+1)
  // Reduces column height by 3.
  def apply(a: Bool, b: Bool, c: Bool, d: Bool): (Bool, Bool, Bool) = {
    val (partialSum, carryOut) = Counter32(a, b, c)
    val (sum, carry) = Counter22(partialSum, d)
    (sum, carry, carryOut)
  }
}

object Counter53 {
  // 5:3 counter:
  // a, b, c, d, e are at column i
  //
  // Returns (sum, carry, carryOut):
  //   - sum      -> next stage, column i
  //   - carry    -> next stage, column i+1
  //   - carryOut -> same stage, column i+1 (feed this as carryIn of column i+1)
  // Reduces column height by 4.
  def apply(a: Bool, b: Bool, c: Bool, d: Bool, e: Bool): (Bool, Bool, Bool) = {
    val (partialSum, carryOut) = Counter32(a, b, c)
    val (sum, carry) = Counter32(partialSum, d, e)
    (sum, carry, carryOut)
  }
}

object Counter22Cin {
  // 2:2 counter:
  // a is at column i
  // carryIn is from the same stage, column i-1
  //
  // Returns (sum, carry):
  //   - sum   -> next stage, column i
  //   - carry -> next stage, column i+1
  // Reduces column height by 1.
  def apply(a: Bool, carryIn: Bool): (Bool, Bool) = {
    val (sum, carry) = Counter22(a, carryIn)
    (sum, carry)
  }
}

object Counter32Cin {
  // 3:2 counter:
  // a, b are at column i
  // carryIn is from the same stage, column i-1
  //
  // Returns (sum, carry):
  //   - sum   -> next stage, column i
  //   - carry -> next stage, column i+1
  // Reduces column height by 2.
  def apply(a: Bool, b: Bool, carryIn: Bool): (Bool, Bool) = {
    val (sum, carry) = Counter32(a, b, carryIn)
    (sum, carry)
  }
}

object Counter43Cin {
  // 4:3 counter:
  // a, b, c are at column i
  // carryIn is from the same stage, column i-1
  //
  // Returns (sum, carry, carryOut):
  //   - sum      -> next stage, column i
  //   - carry    -> next stage, column i+1
  //   - carryOut -> same stage, column i+1 (feed this as carryIn of column i+1)
  // Reduces column height by 3.
  def apply(a: Bool, b: Bool, c: Bool, carryIn: Bool): (Bool, Bool, Bool) = {
    val (sum, carry, carryOut) = Counter43(a, b, c, carryIn)
    (sum, carry, carryOut)
  }
}

object Counter53Cin {
  // 5:3 counter:
  // a, b, c, d are at column i
  // carryIn is from the same stage, column i-1
  //
  // Returns (sum, carry, carryOut):
  //   - sum      -> next stage, column i
  //   - carry    -> next stage, column i+1
  //   - carryOut -> same stage, column i+1 (feed this as carryIn of column i+1)
  // Reduces column height by 4.
  def apply(a: Bool, b: Bool, c: Bool, d: Bool, carryIn: Bool): (Bool, Bool, Bool) = {
    val (sum, carry, carryOut) = Counter53(a, b, c, d, carryIn)
    (sum, carry, carryOut)
  }
}
