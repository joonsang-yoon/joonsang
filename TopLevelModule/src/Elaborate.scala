/**
 * Elaborate.scala
 *
 * Entry point for elaborating a Chisel design into SystemVerilog using CIRCT.
 *
 * This wrapper adds:
 *   - Reflection-based module instantiation by class name
 *   - Parsing of constructor arguments from a command-line string
 *   - Default firtool options for Yosys-friendly SystemVerilog output
 *
 * Usage:
 *   ./mill <MillModule>.runMain Elaborate <ModuleClass>[ (arg1, arg2, ...) ] [ChiselStage options]
 *
 * Examples:
 *   ./mill TopLevelModule.runMain Elaborate TopLevelModule.CustomDesign --target-dir generated/verilog/TopLevelModule/CustomDesign
 *   ./mill TopLevelModule.runMain Elaborate 'TopLevelModule.MyParamModule(32)' --target-dir generated/verilog/TopLevelModule/MyParamModule_32
 */

import chisel3.RawModule
import circt.stage.ChiselStage

import java.lang.reflect.{Constructor, InvocationTargetException}
import scala.collection.mutable
import scala.util.control.NonFatal

object Elaborate {
  // Lowering options configured for Yosys compatibility
  // See: https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
  private val loweringOptions: String = Seq(
    "disallowLocalVariables",
    "disallowPackedArrays"
  ).mkString(",")

  // FIRRTL-to-SystemVerilog conversion options (firtool)
  private val firtoolOptions: Array[String] = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    s"--lowering-options=${loweringOptions}"
  )

  private val supportedArgTypes: String =
    "Int, Long, Boolean, String, BigInt, BigDecimal, Float, Double (numeric literals support 0x/0b/0o and _)."

  def main(args: Array[String]): Unit = {
    if (args.isEmpty || args.contains("--help") || args.contains("-h")) {
      printWrapperUsage()
      // Print ChiselStage help as well. We pass --help explicitly so "no args" won't generate output.
      ChiselStage.emitSystemVerilogFile(new RawModule {}, Array("--help"), firtoolOptions)
      sys.exit(0)
    }

    val moduleSpec = args.head
    val stageArgs = args.tail

    try {
      val outFile =
        ChiselStage.emitSystemVerilogFile(instantiate(moduleSpec), stageArgs, firtoolOptions)

      Console.out.println(s"Successfully generated SystemVerilog for module: ${moduleSpec}")
      Console.out.println(s"Wrote: ${outFile}")
    } catch {
      case e: ElaborateException =>
        Console.err.println(s"Error: ${e.getMessage}")
        sys.exit(1)

      case _: ClassNotFoundException =>
        Console.err.println(s"Error: Could not find class '${extractClassName(moduleSpec)}' on the classpath.")
        Console.err.println("Hint: Use a fully-qualified class name (e.g., 'TopLevelModule.CustomDesign').")
        sys.exit(1)

      case NonFatal(e) =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        Console.err.println(s"Error elaborating module '${moduleSpec}': ${msg}")
        e.printStackTrace()
        sys.exit(1)
    }
  }

  private def printWrapperUsage(): Unit = {
    val msg =
      s"""|Elaborate - generate SystemVerilog from a Chisel module (CIRCT).
          |
          |Usage:
          |  ./mill <MillModule>.runMain Elaborate <ModuleClass>[ (arg1, arg2, ...) ] [ChiselStage options]
          |
          |Examples:
          |  ./mill TopLevelModule.runMain Elaborate TopLevelModule.CustomDesign --target-dir generated/verilog/TopLevelModule/CustomDesign
          |  ./mill TopLevelModule.runMain Elaborate 'TopLevelModule.MyParamModule(32)' --target-dir generated/verilog/TopLevelModule/MyParamModule_32
          |
          |Supported constructor argument types:
          |  ${supportedArgTypes}
          |
          |Default firtool options applied (Yosys-friendly):
          |  ${firtoolOptions.mkString(" ")}
          |
          |ChiselStage options:
          |""".stripMargin
    Console.out.println(msg)
  }

  private final case class ModuleSpec(className: String, args: List[String])

  private sealed abstract class ElaborateException(message: String, cause: Throwable = null)
      extends RuntimeException(message, cause)

  private final case class ModuleParseException(message: String) extends ElaborateException(message)

  private final case class ModuleInstantiationException(message: String, cause: Throwable = null)
      extends ElaborateException(message, cause)

  private def instantiate(moduleSpec: String): RawModule = {
    val spec = parseModuleSpec(moduleSpec)
    val clazz = loadClass(spec.className)

    if (!classOf[RawModule].isAssignableFrom(clazz)) {
      throw ModuleInstantiationException(
        s"Class '${spec.className}' is not a chisel3.RawModule (found: ${clazz.getName})."
      )
    }

    // No-arg module
    if (spec.args.isEmpty) {
      try {
        clazz.getConstructor().newInstance().asInstanceOf[RawModule]
      } catch {
        case _: NoSuchMethodException =>
          throw ModuleInstantiationException(
            s"Class '${spec.className}' does not have a public no-argument constructor.\n" +
              "Note: Scala default arguments are not supported via reflection; provide all constructor arguments explicitly."
          )
      }
    } else {
      instantiateWithArgs(clazz, spec)
    }
  }

  private def instantiateWithArgs(clazz: Class[_], spec: ModuleSpec): RawModule = {
    val publicCtors = clazz.getConstructors.toSeq
    val candidates = publicCtors.filter(_.getParameterCount == spec.args.length)

    if (candidates.isEmpty) {
      throw ModuleInstantiationException(
        s"No public constructor found for class '${spec.className}' accepting ${spec.args.length} argument(s).\n" +
          s"Available public constructors:\n${formatConstructors(publicCtors)}\n" +
          "Note: Scala default arguments are not supported via reflection; you must provide all arguments explicitly."
      )
    }

    val errors = mutable.ListBuffer.empty[String]
    for (ctor <- candidates) {
      try {
        val typedArgs: Array[Object] =
          spec.args.zip(ctor.getParameterTypes).map { case (raw, tpe) => convertArg(raw, tpe) }.toArray
        return ctor.newInstance(typedArgs: _*).asInstanceOf[RawModule]
      } catch {
        case e: IllegalArgumentException =>
          errors += s"  - ${ctorSignature(ctor)}: ${e.getMessage}"

        case e: InvocationTargetException =>
          val root = Option(e.getTargetException).getOrElse(e)
          val msg = Option(root.getMessage).getOrElse("")
          errors += s"  - ${ctorSignature(ctor)}: ${root.getClass.getSimpleName}: ${msg}"

        case NonFatal(e) =>
          val msg = Option(e.getMessage).getOrElse("")
          errors += s"  - ${ctorSignature(ctor)}: ${e.getClass.getSimpleName}: ${msg}"
      }
    }

    throw ModuleInstantiationException(
      s"Could not instantiate '${spec.className}' with arguments: ${spec.args.mkString(", ")}\n" +
        s"Supported argument types: ${supportedArgTypes}\n" +
        "Tried the following constructor(s):\n" +
        errors.mkString("\n")
    )
  }

  private def formatConstructors(ctors: Seq[Constructor[_]]): String =
    if (ctors.isEmpty) "  (none)"
    else ctors.map(c => s"  - ${ctorSignature(c)}").mkString("\n")

  private def ctorSignature(c: Constructor[_]): String = {
    val params = c.getParameterTypes.map(_.getSimpleName).mkString(", ")
    s"${c.getDeclaringClass.getName}(${params})"
  }

  private def loadClass(name: String): Class[_] = {
    val loader = Option(Thread.currentThread().getContextClassLoader).getOrElse(getClass.getClassLoader)
    Class.forName(name, true, loader)
  }

  private def extractClassName(moduleSpec: String): String =
    parseModuleSpec(moduleSpec).className

  /**
   * Parse module specification:
   *   - "pkg.ClassName"
   *   - "pkg.ClassName(arg1, arg2, ...)"
   *
   * The argument list is split on commas at the top level (commas inside quotes or nested ()/[]/{} are preserved).
   */
  private def parseModuleSpec(input: String): ModuleSpec = {
    val s = input.trim
    if (s.isEmpty) throw ModuleParseException("Module name cannot be empty.")

    val openIdx = indexOfTopLevelChar(s, '(')
    if (openIdx < 0) {
      ModuleSpec(s, Nil)
    } else {
      if (!s.endsWith(")")) {
        throw ModuleParseException(s"Malformed module spec '${input}': missing closing ')'.")
      }
      val className = s.substring(0, openIdx).trim
      if (className.isEmpty) {
        throw ModuleParseException(s"Malformed module spec '${input}': missing class name before '('.")
      }
      val inside = s.substring(openIdx + 1, s.length - 1)
      val args = splitArgsTopLevel(inside)
      ModuleSpec(className, args)
    }
  }

  private def indexOfTopLevelChar(s: String, ch: Char): Int = {
    var quote: Char = 0.toChar
    var escaped = false
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (escaped) {
        escaped = false
      } else if (quote != 0) {
        if (c == '\\') escaped = true
        else if (c == quote) quote = 0.toChar
      } else {
        if (c == '"' || c == '\'') quote = c
        else if (c == ch) return i
      }
      i += 1
    }
    -1
  }

  private def splitArgsTopLevel(argsStr: String): List[String] = {
    val trimmed = argsStr.trim
    if (trimmed.isEmpty) return Nil

    val out = mutable.ListBuffer.empty[String]
    val cur = new StringBuilder

    var quote: Char = 0.toChar
    var escaped = false
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0

    def flush(): Unit = {
      val token = cur.toString.trim
      if (token.isEmpty) throw ModuleParseException(s"Empty constructor argument in '${argsStr}'.")
      out += token
      cur.setLength(0)
    }

    def bumpDepth(c: Char): Unit = {
      c match {
        case '(' => parenDepth += 1
        case ')' => parenDepth -= 1
        case '[' => bracketDepth += 1
        case ']' => bracketDepth -= 1
        case '{' => braceDepth += 1
        case '}' => braceDepth -= 1
        case _   =>
      }
      if (parenDepth < 0 || bracketDepth < 0 || braceDepth < 0) {
        throw ModuleParseException(s"Unbalanced delimiters in '${argsStr}'.")
      }
    }

    var i = 0
    while (i < argsStr.length) {
      val c = argsStr.charAt(i)

      if (escaped) {
        cur.append(c)
        escaped = false
      } else if (quote != 0) {
        cur.append(c)
        if (c == '\\') escaped = true
        else if (c == quote) quote = 0.toChar
      } else {
        c match {
          case '"' | '\'' =>
            quote = c
            cur.append(c)

          case '(' | ')' | '[' | ']' | '{' | '}' =>
            bumpDepth(c)
            cur.append(c)

          case ',' if parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 =>
            flush()

          case other =>
            cur.append(other)
        }
      }

      i += 1
    }

    if (quote != 0) throw ModuleParseException(s"Unclosed quote in '${argsStr}'.")
    if (parenDepth != 0 || bracketDepth != 0 || braceDepth != 0) {
      throw ModuleParseException(s"Unbalanced delimiters in '${argsStr}'.")
    }

    if (cur.toString.trim.isEmpty) throw ModuleParseException(s"Trailing comma in '${argsStr}'.")
    flush()

    out.toList
  }

  /**
   * Convert a single argument string to the type expected by reflection.
   */
  private def convertArg(raw: String, targetType: Class[_]): Object = {
    val token = stripOptionalQuotes(raw.trim)

    try {
      if (isIntType(targetType)) {
        java.lang.Integer.valueOf(parseIntLiteral(token))
      } else if (isLongType(targetType)) {
        java.lang.Long.valueOf(parseLongLiteral(token))
      } else if (isBigIntType(targetType)) {
        parseBigIntLiteral(token)
      } else if (isFloatType(targetType)) {
        java.lang.Float.valueOf(parseFloatLiteral(token))
      } else if (isDoubleType(targetType)) {
        java.lang.Double.valueOf(parseDoubleLiteral(token))
      } else if (isBigDecimalType(targetType)) {
        parseBigDecimalLiteral(token)
      } else if (isBooleanType(targetType)) {
        java.lang.Boolean.valueOf(parseBooleanLiteral(token))
      } else if (isStringType(targetType)) {
        token
      } else {
        throw new IllegalArgumentException(s"Unsupported argument type: ${targetType.getName}")
      }
    } catch {
      case e: NumberFormatException =>
        throw new IllegalArgumentException(
          s"Failed to convert argument '${raw}' to ${targetType.getSimpleName}: ${e.getMessage}"
        )
    }
  }

  private def isIntType(t: Class[_]): Boolean =
    (t == java.lang.Integer.TYPE) || (t == classOf[java.lang.Integer])

  private def isLongType(t: Class[_]): Boolean =
    (t == java.lang.Long.TYPE) || (t == classOf[java.lang.Long])

  private def isBooleanType(t: Class[_]): Boolean =
    (t == java.lang.Boolean.TYPE) || (t == classOf[java.lang.Boolean])

  private def isFloatType(t: Class[_]): Boolean =
    (t == java.lang.Float.TYPE) || (t == classOf[java.lang.Float])

  private def isDoubleType(t: Class[_]): Boolean =
    (t == java.lang.Double.TYPE) || (t == classOf[java.lang.Double])

  private def isStringType(t: Class[_]): Boolean =
    t == classOf[String]

  private def isBigIntType(t: Class[_]): Boolean =
    t == classOf[scala.math.BigInt]

  private def isBigDecimalType(t: Class[_]): Boolean =
    t == classOf[scala.math.BigDecimal]

  private def stripOptionalQuotes(s: String): String = {
    val t = s.trim
    if (t.length >= 2) {
      val first = t.charAt(0)
      val last = t.charAt(t.length - 1)
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        unescape(t.substring(1, t.length - 1))
      } else t
    } else t
  }

  private def unescape(s: String): String = {
    val out = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '\\' && i + 1 < s.length) {
        s.charAt(i + 1) match {
          case 'n'   => out.append('\n')
          case 't'   => out.append('\t')
          case 'r'   => out.append('\r')
          case '\\'  => out.append('\\')
          case '"'   => out.append('"')
          case '\''  => out.append('\'')
          case other => out.append(other)
        }
        i += 2
      } else {
        out.append(c)
        i += 1
      }
    }
    out.toString
  }

  private def removeNumericSeparators(s: String): String =
    s.replace("_", "")

  private def looksLikeBasedIntegerLiteral(s: String): Boolean = {
    val noSign =
      if (s.startsWith("+") || s.startsWith("-")) s.substring(1) else s

    noSign.startsWith("0x") || noSign.startsWith("0X") ||
    noSign.startsWith("0b") || noSign.startsWith("0B") ||
    noSign.startsWith("0o") || noSign.startsWith("0O")
  }

  private def parseBigIntLiteral(raw: String): BigInt = {
    val s0 = removeNumericSeparators(raw.trim)
    if (s0.isEmpty) throw new NumberFormatException("empty integer literal")

    var s = s0
    var negative = false
    if (s.startsWith("+")) s = s.substring(1)
    else if (s.startsWith("-")) {
      negative = true
      s = s.substring(1)
    }

    val (base, digits) =
      if (s.startsWith("0x") || s.startsWith("0X")) (16, s.substring(2))
      else if (s.startsWith("0b") || s.startsWith("0B")) (2, s.substring(2))
      else if (s.startsWith("0o") || s.startsWith("0O")) (8, s.substring(2))
      else (10, s)

    if (digits.isEmpty) throw new NumberFormatException(s"invalid integer literal '${raw}'")

    val bi = BigInt(digits, base)
    if (negative) -bi else bi
  }

  private def parseIntLiteral(raw: String): Int = {
    val bi = parseBigIntLiteral(raw)
    if (bi < BigInt(Int.MinValue) || bi > BigInt(Int.MaxValue)) {
      throw new NumberFormatException(s"out of range for Int: '${raw}'")
    }
    bi.toInt
  }

  private def parseLongLiteral(raw: String): Long = {
    val bi = parseBigIntLiteral(raw)
    if (bi < BigInt(Long.MinValue) || bi > BigInt(Long.MaxValue)) {
      throw new NumberFormatException(s"out of range for Long: '${raw}'")
    }
    bi.toLong
  }

  private def parseBooleanLiteral(raw: String): Boolean = {
    raw.trim.toLowerCase match {
      case "true" | "t" | "1"  => true
      case "false" | "f" | "0" => false
      case other =>
        throw new NumberFormatException(s"expected boolean literal (true/false/1/0), got '${other}'")
    }
  }

  private def parseDoubleLiteral(raw: String): Double =
    java.lang.Double.parseDouble(removeNumericSeparators(raw))

  private def parseFloatLiteral(raw: String): Float =
    java.lang.Float.parseFloat(removeNumericSeparators(raw))

  private def parseBigDecimalLiteral(raw: String): scala.math.BigDecimal = {
    val s = removeNumericSeparators(raw.trim)
    if (looksLikeBasedIntegerLiteral(s)) {
      scala.math.BigDecimal(parseBigIntLiteral(s))
    } else {
      scala.math.BigDecimal(s)
    }
  }
}
