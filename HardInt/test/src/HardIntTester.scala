package HardInt.test

import circt.stage.ChiselStage
import chisel3.RawModule
import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.text.SimpleDateFormat
import java.util.Calendar

trait HardIntTester extends AnyFlatSpec with Matchers with ParallelTestExecution {
  def test(name: String, module: () => RawModule, harness: String, dataWidth: Int): String = {
    val loweringOptions = Seq(
      "disallowLocalVariables",
      "disallowPackedArrays"
    ).mkString(",")

    val firtoolOptions = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      s"--lowering-options=${loweringOptions}"
    )

    val timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(Calendar.getInstance.getTime)
    val testArtifactsDir =
      os.Path(sys.env("TEST_OUTPUT_DIR")) / "HardInt" / s"${this.getClass.getSimpleName}_${name}" / timestamp
    os.makeDir.all(testArtifactsDir)

    ChiselStage.emitSystemVerilogFile(module(), Array("--target-dir", testArtifactsDir.toString), firtoolOptions)

    val verilatorCommand = Seq(
      "verilator",
      "--cc",
      "--exe"
    ) ++ (if (sys.env.contains("VCD")) Seq("--trace") else Seq.empty) ++ Seq(
      "-O3",
      "--prefix",
      "dut",
      "--Mdir",
      testArtifactsDir.toString,
      "-CFLAGS",
      s"-DW=${dataWidth} -I${getClass.getResource("/include/").getPath}",
      "-MAKEFLAGS",
      "OPT_FAST=-O3",
      "-f",
      "filelist.f",
      getClass.getResource(s"/csrc/${harness}").getPath,
      "-j",
      "0",
      "--build"
    )

    os.proc(verilatorCommand).call(testArtifactsDir)

    val stdoutFile = testArtifactsDir / s"${name}__stdout.txt"
    os.proc((testArtifactsDir / "dut").toString).call(stdout = stdoutFile)
    os.read(stdoutFile)
  }

  def check(stdout: String): Unit = {
    stdout shouldNot include("expected")
    stdout shouldNot include("Ran 0 tests.")
    stdout should include("No errors found.")
  }
}
