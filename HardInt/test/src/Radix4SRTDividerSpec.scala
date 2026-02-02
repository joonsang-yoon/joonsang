package HardInt.test

import chisel3._
import HardInt._

class Radix4SRTDividerSpec extends HardIntTester {
  "Radix4SRTDivider_dw15" should "pass all combinations" in {
    val out = test(
      name = "Radix4SRTDivider_dw15",
      module = () => new Radix4SRTDivider(dataWidth = 15, useMetadata = false, numXPRs = 32),
      harness = "Radix4SRTDivider_dw15.cpp"
    )
    check(out)
  }

  "Radix4SRTDivider_dw16" should "pass all combinations" in {
    val out = test(
      name = "Radix4SRTDivider_dw16",
      module = () => new Radix4SRTDivider(dataWidth = 16, useMetadata = false, numXPRs = 32),
      harness = "Radix4SRTDivider_dw16.cpp"
    )
    check(out)
  }
}
