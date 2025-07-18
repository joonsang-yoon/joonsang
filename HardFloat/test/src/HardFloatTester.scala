package HardFloat.test

import circt.stage.ChiselStage
import chisel3.RawModule
import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.text.SimpleDateFormat
import java.util.Calendar
import scala.collection.parallel.CollectionConverters._

/**
 * Base trait for testing HardFloat floating-point units.
 *
 * This trait provides common functionality for testing floating-point hardware modules
 * generated by HardFloat against Berkeley TestFloat, the industry-standard floating-point
 * test suite. It handles:
 * - Test generation and execution using Verilator
 * - Comparison against TestFloat golden results
 * - Support for different rounding modes and tininess detection
 * - Parallel test execution for better performance
 */
trait HardFloatTester extends AnyFlatSpec with Matchers with ParallelTestExecution {

  /**
   * Returns the exponent width for a given floating-point format.
   *
   * @param f The total width of the floating-point format (16, 32, or 64)
   * @return The number of exponent bits as per IEEE 754:
   *         - 16-bit (half): 5 exponent bits
   *         - 32-bit (single): 8 exponent bits  
   *         - 64-bit (double): 11 exponent bits
   */
  def exp(f: Int): Int = f match {
    case 16 => 5
    case 32 => 8
    case 64 => 11
  }

  /**
   * Returns the significand width (including implicit bit) for a given floating-point format.
   *
   * @param f The total width of the floating-point format (16, 32, or 64)
   * @return The number of significand bits (mantissa + 1 implicit bit):
   *         - 16-bit (half): 11 bits (10 stored + 1 implicit)
   *         - 32-bit (single): 24 bits (23 stored + 1 implicit)
   *         - 64-bit (double): 53 bits (52 stored + 1 implicit)
   */
  def sig(f: Int): Int = f match {
    case 16 => 11
    case 32 => 24
    case 64 => 53
  }

  /**
   * Supported rounding modes as defined by IEEE 754.
   * Each tuple contains:
   * - The TestFloat command line argument for the rounding mode
   * - The corresponding numeric code for the hardware module
   *
   * Rounding modes:
   * - rnear_even (0): Round to nearest, ties to even (default)
   * - rminMag (1): Round toward zero (truncation)
   * - rmin (2): Round toward negative infinity (floor)
   * - rmax (3): Round toward positive infinity (ceiling)
   * - rnear_maxMag (4): Round to nearest, ties away from zero
   * - rodd (6): Round to odd (used in some arithmetic operations)
   *
   * Note: Mode 5 is not defined here (likely reserved)
   */
  val roundings = Seq(
    "-rnear_even" -> "0",
    "-rminMag" -> "1",
    "-rmin" -> "2",
    "-rmax" -> "3",
    "-rnear_maxMag" -> "4",
    "-rodd" -> "6"
  )

  /**
   * Validates test results by checking the output from TestFloat comparisons.
   *
   * @param stdouts Sequence of stdout outputs from test runs
   *
   * The function checks that:
   * - No "expected" strings appear (indicating mismatches)
   * - Tests actually ran (not "Ran 0 tests")
   * - All tests passed ("No errors found" appears)
   */
  def check(stdouts: Seq[String]): Unit = {
    stdouts.foreach(_ shouldNot include("expected"))
    stdouts.foreach(_ shouldNot include("Ran 0 tests."))
    stdouts.foreach(_ should include("No errors found."))
  }

  /**
   * Test helper for standard floating-point operations with rounding modes.
   *
   * This version automatically tests all rounding modes with both tininess
   * detection options (before and after rounding).
   *
   * @param name Name of the test/module
   * @param module Factory function to create the hardware module
   * @param softfloatArg TestFloat command arguments for the operation
   * @return Sequence of test output strings
   */
  def test(name: String, module: () => RawModule, softfloatArg: Seq[String]): Seq[String] = {
    // Generate test configurations for all combinations of:
    // - 6 rounding modes
    // - 2 tininess detection modes (before/after rounding)
    // Total: 12 test configurations per operation
    val (softfloatArgs, dutArgs) = (roundings.map { case (s, d) =>
      // Tininess before rounding configurations
      (Seq(s, "-tininessbefore") ++ softfloatArg, Seq(d, "0"))
    } ++ roundings.map { case (s, d) =>
      // Tininess after rounding configurations
      (Seq(s, "-tininessafter") ++ softfloatArg, Seq(d, "1"))
    }).unzip
    test(name, module, "test.cpp", softfloatArgs, Some(dutArgs))
  }

  /**
   * Core test execution function that generates Verilog, compiles with Verilator,
   * and runs tests against TestFloat.
   *
   * @param name Test/module name
   * @param module Factory function to create the hardware module
   * @param harness C++ test harness filename
   * @param softfloatArgs TestFloat command line arguments for each test configuration
   * @param dutArgs Optional DUT (Design Under Test) arguments for each configuration
   * @return Sequence of test output strings
   *
   * The function:
   * 1. Generates Verilog from the Chisel module
   * 2. Compiles it with Verilator along with the C++ test harness
   * 3. Runs TestFloat to generate test vectors
   * 4. Feeds vectors to the DUT and captures results
   * 5. Returns output for validation
   */
  def test(
    name:          String,
    module:        () => RawModule,
    harness:       String,
    softfloatArgs: Seq[Seq[String]],
    dutArgs:       Option[Seq[Seq[String]]] = None
  ): Seq[String] = {
    // Lowering options configured for Yosys compatibility
    // - disallowLocalVariables: Required since Yosys doesn't parse automatic variables
    // - disallowPackedArrays: Required since Yosys doesn't accept packed arrays
    // @see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
    val loweringOptions = Seq(
      "disallowLocalVariables",
      "disallowPackedArrays"
    ).mkString(",")

    // CIRCT firtool options for FIRRTL-to-SystemVerilog conversion
    // - disable-all-randomization: Removes randomization constructs from generated Verilog
    // - strip-debug-info: Removes debug information to produce cleaner output
    // - lowering-options: Apply Yosys-compatible lowering options defined above
    val firtoolOptions = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      s"--lowering-options=${loweringOptions}"
    )

    // Create timestamped directory for test artifacts
    val timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(Calendar.getInstance.getTime)
    val testArtifactsDir =
      os.Path(sys.env("TEST_OUTPUT_DIR")) / "HardFloat" / s"${this.getClass.getSimpleName}_${name}" / timestamp
    os.makeDir.all(testArtifactsDir)

    // Generate Verilog from Chisel module
    ChiselStage.emitSystemVerilogFile(module(), Array("--target-dir", testArtifactsDir.toString), firtoolOptions)

    // C compiler flags for test harness
    val cflags = Seq(
      "-O3",
      s"-I${getClass.getResource("/include/").getPath}",
      s"-include ${getClass.getResource(s"/include/${name}.h").getPath}"
    ).mkString(" ")

    // Build Verilator command
    val verilatorBuildCommand = Seq(
      "verilator",
      "-cc", // C++ output mode
      "--exe", // Build executable
      "--build", // Build the model
      "-f",
      "filelist.f", // Input filelist
      getClass.getResource(s"/csrc/${harness}").getPath, // C++ harness file
      "--prefix",
      "dut", // Output file prefix
      "--Mdir",
      testArtifactsDir.toString, // Output directory
      "-CFLAGS",
      cflags, // C compiler flags
      "-O3", // Optimization level
      "-j",
      "0" // Parallel jobs (0 = auto)
    ) ++ (if (sys.env.contains("VCD")) Seq("--trace") else Seq.empty)

    // Compile the design
    os.proc(verilatorBuildCommand).call(testArtifactsDir)

    /**
     * Executes a single test configuration.
     *
     * @param softfloatArg TestFloat arguments
     * @param dutArg DUT arguments (rounding mode, tininess)
     * @return Test output as string
     */
    def executeAndLog(softfloatArg: Seq[String], dutArg: Seq[String]): String = {
      // Create unique identifier for this configuration
      val configId = (softfloatArg ++ dutArg).mkString("_")
      val stdoutFile = testArtifactsDir / s"${name}__${configId}.txt"
      val vcdFile = testArtifactsDir / s"${name}__${configId}.vcd"

      // Run test: pipe TestFloat output to DUT, capture results
      os.proc((testArtifactsDir / "dut").toString +: dutArg)
        .call(
          stdin = os.proc("testfloat_gen" +: softfloatArg).spawn().stdout, // TestFloat generates test vectors
          stdout = stdoutFile, // Capture test results
          stderr = vcdFile // VCD trace output (if enabled)
        )
      os.read(stdoutFile)
    }

    // Execute all test configurations in parallel for speed
    (if (dutArgs.isDefined) {
       require(softfloatArgs.size == dutArgs.get.size, "size of softfloatArgs and dutArgs should be same.")
       softfloatArgs.zip(dutArgs.get).par.map { case (s, d) => executeAndLog(s, d) }
     } else {
       softfloatArgs.par.map { s => executeAndLog(s, Seq.empty) }
     }).seq
  }
}

import HardFloat.Consts

class AddRecFNSpec extends HardFloatTester {
  def test(f: Int): Seq[String] = {
    test(
      s"AddRecF${f}",
      () => new ValExec_AddRecFN(exp(f), sig(f)),
      Seq(s"f${f}_add")
    )
  }

  "AddRecF16" should "pass" in {
    check(test(16))
  }
  "AddRecF32" should "pass" in {
    check(test(32))
  }
  "AddRecF64" should "pass" in {
    check(test(64))
  }
}

class CompareRecFNSpec extends HardFloatTester {
  def test(f: Int, fn: String): Seq[String] = {
    val generator = fn match {
      case "lt" => () => new ValExec_CompareRecFN_lt(exp(f), sig(f))
      case "le" => () => new ValExec_CompareRecFN_le(exp(f), sig(f))
      case "eq" => () => new ValExec_CompareRecFN_eq(exp(f), sig(f))
    }
    test(
      s"CompareRecF${f}_${fn}",
      generator,
      "CompareRecFN.cpp",
      Seq(Seq(s"f${f}_${fn}"))
    )
  }

  "CompareRecF16_lt" should "pass" in {
    check(test(16, "lt"))
  }
  "CompareRecF32_lt" should "pass" in {
    check(test(32, "lt"))
  }
  "CompareRecF64_lt" should "pass" in {
    check(test(64, "lt"))
  }
  "CompareRecF16_le" should "pass" in {
    check(test(16, "le"))
  }
  "CompareRecF32_le" should "pass" in {
    check(test(32, "le"))
  }
  "CompareRecF64_le" should "pass" in {
    check(test(64, "le"))
  }
  "CompareRecF16_eq" should "pass" in {
    check(test(16, "eq"))
  }
  "CompareRecF32_eq" should "pass" in {
    check(test(32, "eq"))
  }
  "CompareRecF64_eq" should "pass" in {
    check(test(64, "eq"))
  }
}

class DivSqrtRecF64Spec extends HardFloatTester {
  def test(fn: String): Seq[String] = {
    val generator = fn match {
      case "div"  => () => new ValExec_DivSqrtRecF64_div
      case "sqrt" => () => new ValExec_DivSqrtRecF64_sqrt
    }
    test(
      s"DivSqrtRecF64_${fn}",
      generator,
      (if (fn == "sqrt") Seq("-level2") else Seq.empty) ++ Seq(s"f64_${fn}")
    )
  }

  "DivSqrtRecF64_div" should "pass" in {
    check(test("div"))
  }
  "DivSqrtRecF64_sqrt" should "pass" in {
    check(test("sqrt"))
  }
}

class DivSqrtRecFn_smallSpec extends HardFloatTester {
  def test(f: Int, fn: String): Seq[String] = {
    def generator(options: Int): () => RawModule = fn match {
      case "div"  => () => new ValExec_DivSqrtRecFN_small_div(exp(f), sig(f), options)
      case "sqrt" => () => new ValExec_DivSqrtRecFN_small_sqrt(exp(f), sig(f), options)
    }
    test(
      s"DivSqrtRecF${f}_small_${fn}",
      generator(0),
      (if (fn == "sqrt") Seq("-level2") else Seq.empty) ++ Seq(s"f${f}_${fn}")
    )
    test(
      s"DivSqrtRecF${f}_small_${fn}",
      generator(Consts.divSqrtOpt_twoBitsPerCycle),
      (if (fn == "sqrt") Seq("-level2") else Seq.empty) ++ Seq(s"f${f}_${fn}")
    )
  }

  "DivSqrtRecF16_small_div" should "pass" in {
    check(test(16, "div"))
  }
  "DivSqrtRecF32_small_div" should "pass" in {
    check(test(32, "div"))
  }
  "DivSqrtRecF64_small_div" should "pass" in {
    check(test(64, "div"))
  }
  "DivSqrtRecF16_small_sqrt" should "pass" in {
    check(test(16, "sqrt"))
  }
  "DivSqrtRecF32_small_sqrt" should "pass" in {
    check(test(32, "sqrt"))
  }
  "DivSqrtRecF64_small_sqrt" should "pass" in {
    check(test(64, "sqrt"))
  }
}

class FnFromRecFnSpec extends HardFloatTester {
  def test(f: Int): Seq[String] = {
    test(
      s"F${f}FromRecF${f}",
      () => new ValExec_FNFromRecFN(exp(f), sig(f)),
      "FNFromRecFN.cpp",
      Seq(Seq("-level2", s"-f${f}"))
    )
  }

  "F16FromRecF16" should "pass" in {
    check(test(16))
  }
  "F32FromRecF32" should "pass" in {
    check(test(32))
  }
  "F64FromRecF64" should "pass" in {
    check(test(64))
  }
}

class UINToRecFNSpec extends HardFloatTester {
  def test(i: Int, f: Int): Seq[String] = {
    test(
      s"UI${i}ToRecF${f}",
      () => new ValExec_UINToRecFN(i, exp(f), sig(f)),
      Seq("-level2", s"ui${i}_to_f${f}")
    )
  }

  "UI32ToRecF16" should "pass" in {
    check(test(32, 16))
  }
  "UI32ToRecF32" should "pass" in {
    check(test(32, 32))
  }
  "UI32ToRecF64" should "pass" in {
    check(test(32, 64))
  }
  "UI64ToRecF16" should "pass" in {
    check(test(64, 16))
  }
  "UI64ToRecF32" should "pass" in {
    check(test(64, 32))
  }
  "UI64ToRecF64" should "pass" in {
    check(test(64, 64))
  }
}

class INToRecFNSpec extends HardFloatTester {
  def test(i: Int, f: Int): Seq[String] = {
    test(
      s"I${i}ToRecF${f}",
      () => new ValExec_INToRecFN(i, exp(f), sig(f)),
      Seq("-level2", s"i${i}_to_f${f}")
    )
  }

  "I32ToRecF16" should "pass" in {
    check(test(32, 16))
  }
  "I32ToRecF32" should "pass" in {
    check(test(32, 32))
  }
  "I32ToRecF64" should "pass" in {
    check(test(32, 64))
  }
  "I64ToRecF16" should "pass" in {
    check(test(64, 16))
  }
  "I64ToRecF32" should "pass" in {
    check(test(64, 32))
  }
  "I64ToRecF64" should "pass" in {
    check(test(64, 64))
  }
}

class MulAddRecFNSpec extends HardFloatTester {
  def test(f: Int, fn: String): Seq[String] = {
    test(
      s"MulAddRecF${f}${fn match {
          case "mulAdd" => ""
          case "add"    => "_add"
          case "mul"    => "_mul"
        }}",
      () =>
        fn match {
          case "mulAdd" => new ValExec_MulAddRecFN(exp(f), sig(f))
          case "add"    => new ValExec_MulAddRecFN_add(exp(f), sig(f))
          case "mul"    => new ValExec_MulAddRecFN_mul(exp(f), sig(f))
        },
      Seq(s"f${f}_${fn}")
    )
  }

  "MulAddRecF16" should "pass" in {
    check(test(16, "mulAdd"))
  }
  "MulAddRecF32" should "pass" in {
    check(test(32, "mulAdd"))
  }
  "MulAddRecF64" should "pass" in {
    check(test(64, "mulAdd"))
  }
  "MulAddRecF16_add" should "pass" in {
    check(test(16, "add"))
  }
  "MulAddRecF32_add" should "pass" in {
    check(test(32, "add"))
  }
  "MulAddRecF64_add" should "pass" in {
    check(test(64, "add"))
  }
  "MulAddRecF16_mul" should "pass" in {
    check(test(16, "mul"))
  }
  "MulAddRecF32_mul" should "pass" in {
    check(test(32, "mul"))
  }
  "MulAddRecF64_mul" should "pass" in {
    check(test(64, "mul"))
  }
}

class MulRecFNSpec extends HardFloatTester {
  def test(f: Int): Seq[String] = {
    test(
      s"MulRecF${f}",
      () => new ValExec_MulRecFN(exp(f), sig(f)),
      Seq(s"f${f}_mul")
    )
  }

  "MulRecF16" should "pass" in {
    check(test(16))
  }
  "MulRecF32" should "pass" in {
    check(test(32))
  }
  "MulRecF64" should "pass" in {
    check(test(64))
  }
}

class RecFNToUINSpec extends HardFloatTester {
  def test(f: Int, i: Int): Seq[String] = {
    // For integer conversion, only rounding mode matters (no tininess)
    val (softfloatArgs, dutArgs) = roundings.map { case (s, d) =>
      (s +: Seq("-exact", "-level2", s"f${f}_to_ui${i}"), Seq(d))
    }.unzip
    test(
      s"RecF${f}ToUI${i}",
      () => new ValExec_RecFNToUIN(exp(f), sig(f), i),
      "RecFNToUIN.cpp",
      softfloatArgs,
      Some(dutArgs)
    )
  }

  "RecF16ToUI32" should "pass" in {
    check(test(16, 32))
  }
  "RecF16ToUI64" should "pass" in {
    check(test(16, 64))
  }
  "RecF32ToUI32" should "pass" in {
    check(test(32, 32))
  }
  "RecF32ToUI64" should "pass" in {
    check(test(32, 64))
  }
  "RecF64ToUI32" should "pass" in {
    check(test(64, 32))
  }
  "RecF64ToUI64" should "pass" in {
    check(test(64, 64))
  }
}

class RecFNToINSpec extends HardFloatTester {
  def test(f: Int, i: Int): Seq[String] = {
    // For integer conversion, only rounding mode matters (no tininess)
    val (softfloatArgs, dutArgs) = roundings.map { case (s, d) =>
      (s +: Seq("-exact", "-level2", s"f${f}_to_i${i}"), Seq(d))
    }.unzip
    test(
      s"RecF${f}ToI${i}",
      () => new ValExec_RecFNToIN(exp(f), sig(f), i),
      "RecFNToIN.cpp",
      softfloatArgs,
      Some(dutArgs)
    )
  }

  "RecF16ToI32" should "pass" in {
    check(test(16, 32))
  }
  "RecF16ToI64" should "pass" in {
    check(test(16, 64))
  }
  "RecF32ToI32" should "pass" in {
    check(test(32, 32))
  }
  "RecF32ToI64" should "pass" in {
    check(test(32, 64))
  }
  "RecF64ToI32" should "pass" in {
    check(test(64, 32))
  }
  "RecF64ToI64" should "pass" in {
    check(test(64, 64))
  }
}

class RecFNToRecFNSpec extends HardFloatTester {
  def test(f0: Int, f1: Int): Seq[String] = {
    test(
      s"RecF${f0}ToRecF${f1}",
      () => new ValExec_RecFNToRecFN(exp(f0), sig(f0), exp(f1), sig(f1)),
      Seq("-level2", s"f${f0}_to_f${f1}")
    )
  }

  "RecF16ToRecF32" should "pass" in {
    check(test(16, 32))
  }
  "RecF16ToRecF64" should "pass" in {
    check(test(16, 64))
  }
  "RecF32ToRecF16" should "pass" in {
    check(test(32, 16))
  }
  "RecF32ToRecF64" should "pass" in {
    check(test(32, 64))
  }
  "RecF64ToRecF16" should "pass" in {
    check(test(64, 16))
  }
  "RecF64ToRecF32" should "pass" in {
    check(test(64, 32))
  }
}
