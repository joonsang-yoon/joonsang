package HardInt.test

import chisel3._
import HardInt._

class Radix4BoothMultiplierSpec extends HardIntTester {
  def runTest(dataWidth: Int): Unit = {
    val out = test(
      name = s"Radix4BoothMultiplier_dw${dataWidth}",
      module = () => Radix4BoothMultiplier(dataWidth = dataWidth, initHeight = 2),
      harness = "Radix4BoothMultiplier.cpp",
      dataWidth = dataWidth
    )
    check(out)
  }

  "Radix4BoothMultiplier_dw15" should "pass all combinations" in {
    runTest(15)
  }

  "Radix4BoothMultiplier_dw16" should "pass all combinations" in {
    runTest(16)
  }
}
