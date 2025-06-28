/**
 * Elaborate.scala
 *
 * This file is responsible for elaborating the Chisel design into SystemVerilog. It is the main entry point for
 * generating the hardware description.
 */

import circt.stage.ChiselStage
import chisel3.RawModule

/**
 * Object responsible for elaborating the Chisel design into SystemVerilog. This is the main entry point for generating
 * the hardware description.
 */
object Elaborate extends App {
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

  // Handle help or no arguments by showing ChiselStage help
  if (args.isEmpty || args.contains("--help") || args.contains("-h")) {
    // Create a dummy module just to get help output
    ChiselStage.emitSystemVerilogFile(new TopLevelModule.CustomDesign(), args, firtoolOptions)
    sys.exit(0)
  }

  // Extract module name and remaining arguments
  val moduleName = args.head
  val remainingArgs = args.tail

  try {
    def createModule(): RawModule = moduleName match {
      case _ =>
        val clazz = Class.forName(moduleName)
        val constructor = clazz.getConstructor()
        constructor.newInstance().asInstanceOf[RawModule]
    }

    // Generate SystemVerilog file from the Chisel design
    ChiselStage.emitSystemVerilogFile(createModule(), remainingArgs, firtoolOptions)

    println(s"Successfully generated SystemVerilog for module: ${moduleName}")
  } catch {
    case _: ClassNotFoundException =>
      println(s"Error: Module '${moduleName}' not found")
      println("Make sure the module name includes the full package path if applicable")
      sys.exit(1)
    case e: Exception =>
      println(s"Error instantiating module '${moduleName}': ${e.getMessage}")
      sys.exit(1)
  }
}
