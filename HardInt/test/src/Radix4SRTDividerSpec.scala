package HardInt.test

import chisel3._
import HardInt._

class Radix4SRTDividerSpec extends HardIntTester {
  def runTest(dataWidth: Int): Unit = {
    val out = test(
      name = s"Radix4SRTDivider_dw${dataWidth}",
      module = () => Radix4SRTDivider(dataWidth = dataWidth),
      harness = "Radix4SRTDivider.cpp",
      dataWidth = dataWidth
    )
    check(out)
  }

  "Radix4SRTDivider_dw15" should "pass all combinations" in {
    runTest(15)
  }

  "Radix4SRTDivider_dw16" should "pass all combinations" in {
    runTest(16)
  }
}
