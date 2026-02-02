package HardInt.test

import chisel3._
import HardInt._

class Radix4BoothMultiplierSpec extends HardIntTester {
  "Radix4BoothMultiplier_dw15_initHeight2" should "pass all combinations" in {
    val out = test(
      name = "Radix4BoothMultiplier_dw15_initHeight2",
      module = () => new Radix4BoothMultiplier(dataWidth = 15, useMetadata = false, numXPRs = 32, initHeight = 2),
      harness = "Radix4BoothMultiplier_dw15.cpp"
    )
    check(out)
  }

  "Radix4BoothMultiplier_dw16_initHeight2" should "pass all combinations" in {
    val out = test(
      name = "Radix4BoothMultiplier_dw16_initHeight2",
      module = () => new Radix4BoothMultiplier(dataWidth = 16, useMetadata = false, numXPRs = 32, initHeight = 2),
      harness = "Radix4BoothMultiplier_dw16.cpp"
    )
    check(out)
  }
}
