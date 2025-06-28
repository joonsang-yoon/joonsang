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
 *   ./mill <MillModule>.runMain Elaborate <ModuleClass>[(arg1, arg2, ...)] [ChiselStage options]
 *
 * Examples:
 *   ./mill TopLevelModule.runMain Elaborate TopLevelModule.CustomDesign --target-dir generated/verilog/TopLevelModule/CustomDesign
 *   ./mill TopLevelModule.runMain Elaborate 'TopLevelModule.MyParamModule(32)' --target-dir generated/verilog/TopLevelModule/MyParamModule_32
 */

import chisel3.RawModule
import circt.stage.ChiselStage

import java.lang.reflect.{Constructor, InvocationTargetException, Method}
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
    "Int, Boolean"

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

      case e: LinkageError =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        Console.err.println(
          s"Error: Linkage error while loading '${extractClassName(moduleSpec)}': ${msg}"
        )
        Console.err.println(
          "Hint: This usually means a dependency is missing or there is a version mismatch on the classpath."
        )
        e.printStackTrace()
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
          |  ./mill <MillModule>.runMain Elaborate <ModuleClass>[(arg1, arg2, ...)] [ChiselStage options]
          |
          |Examples:
          |  ./mill TopLevelModule.runMain Elaborate TopLevelModule.CustomDesign --target-dir generated/verilog/TopLevelModule/CustomDesign
          |  ./mill TopLevelModule.runMain Elaborate 'TopLevelModule.MyParamModule(32)' --target-dir generated/verilog/TopLevelModule/MyParamModule_32
          |
          |Supported argument types (constructors / companion apply):
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

    // Delegate to the ctor-matching logic even for the 0-arg case so we get consistent error handling.
    instantiateWithArgs(clazz, spec)
  }

  private final case class CtorMatch(ctor: Constructor[_], args: Array[Object], score: Int)

  private final case class ApplyMatch(method: Method, args: Array[Object], score: Int)

  private def instantiateWithArgs(clazz: Class[_], spec: ModuleSpec): RawModule = {
    val publicCtors = clazz.getConstructors.toSeq.sortBy(ctorSignature)
    val candidates = publicCtors.filter(_.getParameterCount == spec.args.length)

    if (candidates.isEmpty) {
      // Many Chisel modules provide a companion `object Foo { def apply(...) = new Foo(...) }` with a CLI-friendly
      // signature, even when the underlying class constructor has additional parameters.
      // If no ctor matches by arity, try the companion `apply` methods before failing.
      instantiateViaCompanionApply(spec) match {
        case Right(m) =>
          return m

        case Left(applyErr) =>
          throw ModuleInstantiationException(
            s"No public constructor found for class '${spec.className}' accepting ${spec.args.length} argument(s).\n" +
              s"Available public constructors:\n${formatConstructors(publicCtors)}\n" +
              applyErr.getMessage,
            applyErr.getCause
          )
      }
    }

    // First, try to *match* arguments to parameter types without instantiating the module.
    // Instantiating multiple top-level modules can confuse Chisel's global builder state, so we avoid that.
    val matches = mutable.ListBuffer.empty[CtorMatch]
    val errors = mutable.ListBuffer.empty[String]

    for (ctor <- candidates) {
      try {
        val converted = spec.args.zip(ctor.getParameterTypes).map { case (raw, tpe) =>
          convertArg(raw, tpe)
        }
        val typedArgs = converted.map(_.value).toArray
        val score = converted.map(_.score).sum
        matches += CtorMatch(ctor, typedArgs, score)
      } catch {
        case e: IllegalArgumentException =>
          errors += s"  - ${ctorSignature(ctor)}: ${e.getMessage}"
        case NonFatal(e) =>
          val msg = Option(e.getMessage).getOrElse("")
          errors += s"  - ${ctorSignature(ctor)}: ${e.getClass.getSimpleName}: ${msg}"
      }
    }

    if (matches.isEmpty) {
      // If ctor-arity matches but we couldn't type-match arguments (e.g., ctor takes unsupported types),
      // try the companion `apply` overloads as a fallback.
      instantiateViaCompanionApply(spec) match {
        case Right(m) =>
          return m

        case Left(applyErr) =>
          throw ModuleInstantiationException(
            s"Could not match any public constructor for '${spec.className}' with arguments: ${spec.args.mkString(", ")}\n" +
              s"Supported argument types: ${supportedArgTypes}\n" +
              "Tried the following constructor(s):\n" +
              errors.mkString("\n") +
              "\n" +
              applyErr.getMessage,
            applyErr.getCause
          )
      }
    }

    val bestScore = matches.map(_.score).max
    val best = matches.filter(_.score == bestScore).toList

    if (best.lengthCompare(1) != 0) {
      val bestSigs =
        best
          .sortBy(m => ctorSignature(m.ctor))
          .map(m => s"  - ${ctorSignature(m.ctor)} (score=${m.score})")
          .mkString("\n")
      throw ModuleInstantiationException(
        s"Ambiguous constructor match for '${spec.className}' with arguments: ${spec.args.mkString(", ")}\n" +
          "Multiple constructors match equally well:\n" +
          bestSigs + "\n" +
          "Hint: Use explicit boolean literals (true/false) to prefer Boolean parameters when constructors are overloaded."
      )
    }

    val chosen = best.head
    try {
      chosen.ctor.newInstance(chosen.args: _*).asInstanceOf[RawModule]
    } catch {
      case e: InvocationTargetException =>
        val root = Option(e.getTargetException).getOrElse(e)
        val msg = Option(root.getMessage).getOrElse("")
        throw ModuleInstantiationException(
          s"Constructor ${ctorSignature(chosen.ctor)} threw: ${root.getClass.getSimpleName}: ${msg}",
          root
        )
      case NonFatal(e) =>
        val msg = Option(e.getMessage).getOrElse("")
        throw ModuleInstantiationException(
          s"Failed instantiating ${ctorSignature(chosen.ctor)}: ${e.getClass.getSimpleName}: ${msg}",
          e
        )
    }
  }

  /**
   * Attempt instantiation via Scala companion object `apply(...)`.
   *
   * This supports common Chisel patterns like:
   *   object Foo { def apply(width: Int): Foo = new Foo(width, () => new Bundle {}) }
   */
  private def instantiateViaCompanionApply(spec: ModuleSpec): Either[ModuleInstantiationException, RawModule] = {
    val companion = loadCompanionObjectInstance(spec.className)
    if (companion.isEmpty) {
      return Left(
        ModuleInstantiationException(
          s"Also tried Scala companion object '${spec.className}', but it was not found on the classpath.\n" +
            "Hint: Define a companion `object` with an `apply(...)` overload that matches your CLI arguments."
        )
      )
    }

    val obj = companion.get

    val allApplyMethods =
      obj.getClass.getMethods.toSeq.filter(_.getName == "apply").sortBy(applySignature)

    val arityMatches = allApplyMethods.filter(_.getParameterCount == spec.args.length)
    val candidates = arityMatches.filter(m => classOf[RawModule].isAssignableFrom(m.getReturnType))

    if (candidates.isEmpty) {
      return Left(
        ModuleInstantiationException(
          s"Also tried companion object apply methods for '${spec.className}', but found no `apply` returning RawModule " +
            s"accepting ${spec.args.length} argument(s).\n" +
            s"Available companion apply methods:\n${formatApplyMethods(allApplyMethods)}\n" +
            "Note: Scala default arguments are not supported via reflection; you must provide all arguments explicitly " +
            "unless you offer an `apply(...)` overload with the desired arity."
        )
      )
    }

    // Match arguments to parameter types without invoking `apply` multiple times.
    val matches = mutable.ListBuffer.empty[ApplyMatch]
    val errors = mutable.ListBuffer.empty[String]

    for (m <- candidates) {
      try {
        val converted = spec.args.zip(m.getParameterTypes).map { case (raw, tpe) =>
          convertArg(raw, tpe)
        }
        val typedArgs = converted.map(_.value).toArray
        val score = converted.map(_.score).sum
        matches += ApplyMatch(m, typedArgs, score)
      } catch {
        case e: IllegalArgumentException =>
          errors += s"  - ${applySignature(m)}: ${e.getMessage}"
        case NonFatal(e) =>
          val msg = Option(e.getMessage).getOrElse("")
          errors += s"  - ${applySignature(m)}: ${e.getClass.getSimpleName}: ${msg}"
      }
    }

    if (matches.isEmpty) {
      return Left(
        ModuleInstantiationException(
          s"Also tried companion object apply methods for '${spec.className}', but could not match argument types for: " +
            s"${spec.args.mkString(", ")}\n" +
            s"Supported argument types: ${supportedArgTypes}\n" +
            "Tried the following apply method(s):\n" +
            errors.mkString("\n")
        )
      )
    }

    val bestScore = matches.map(_.score).max
    val best = matches.filter(_.score == bestScore).toList

    if (best.lengthCompare(1) != 0) {
      val bestSigs =
        best
          .sortBy(m => applySignature(m.method))
          .map(m => s"  - ${applySignature(m.method)} (score=${m.score})")
          .mkString("\n")

      return Left(
        ModuleInstantiationException(
          s"Ambiguous companion apply match for '${spec.className}' with arguments: ${spec.args.mkString(", ")}\n" +
            "Multiple apply methods match equally well:\n" +
            bestSigs + "\n" +
            "Hint: Use explicit boolean literals (true/false) to prefer Boolean parameters when overloads exist."
        )
      )
    }

    val chosen = best.head
    try {
      val inst = chosen.method.invoke(obj, chosen.args: _*).asInstanceOf[RawModule]
      Right(inst)
    } catch {
      case e: InvocationTargetException =>
        val root = Option(e.getTargetException).getOrElse(e)
        val msg = Option(root.getMessage).getOrElse("")
        Left(
          ModuleInstantiationException(
            s"Companion apply ${applySignature(chosen.method)} threw: ${root.getClass.getSimpleName}: ${msg}",
            root
          )
        )
      case NonFatal(e) =>
        val msg = Option(e.getMessage).getOrElse("")
        Left(
          ModuleInstantiationException(
            s"Failed invoking companion apply ${applySignature(chosen.method)}: ${e.getClass.getSimpleName}: ${msg}",
            e
          )
        )
    }
  }

  private def loadCompanionObjectInstance(className: String): Option[AnyRef] = {
    val moduleClassName = if (className.endsWith("$")) className else s"${className}$$"
    try {
      val moduleClass = loadClass(moduleClassName)
      val field = moduleClass.getField("MODULE$")
      Option(field.get(null)).map(_.asInstanceOf[AnyRef])
    } catch {
      case _: ClassNotFoundException => None
      case _: NoSuchFieldException   => None
      case NonFatal(_) => None
    }
  }

  private def formatApplyMethods(methods: Seq[Method]): String =
    if (methods.isEmpty) "  (none)"
    else methods.sortBy(applySignature).map(m => s"  - ${applySignature(m)}").mkString("\n")

  private def applySignature(m: Method): String = {
    val owner = prettyDeclaringClassName(m.getDeclaringClass)
    val params = m.getParameterTypes.map(prettyTypeName).mkString(", ")
    val ret = prettyTypeName(m.getReturnType)
    s"${owner}.apply(${params}): ${ret}"
  }

  private def prettyDeclaringClassName(c: Class[_]): String = {
    val n = c.getName
    if (n.endsWith("$")) n.dropRight(1) else n
  }

  private def formatConstructors(ctors: Seq[Constructor[_]]): String =
    if (ctors.isEmpty) "  (none)"
    else ctors.sortBy(ctorSignature).map(c => s"  - ${ctorSignature(c)}").mkString("\n")

  private def ctorSignature(c: Constructor[_]): String = {
    val params = c.getParameterTypes.map(prettyTypeName).mkString(", ")
    s"${c.getDeclaringClass.getName}(${params})"
  }

  private def prettyTypeName(t: Class[_]): String =
    if (isIntType(t)) "Int"
    else if (isBooleanType(t)) "Boolean"
    else t.getSimpleName

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

  private final case class ConvertedArg(value: Object, score: Int)

  /**
   * Convert a single argument string to the type expected by reflection.
   *
   * Besides the converted value, we also compute a small heuristic "score" used to select
   * the best match when multiple constructors have the same arity.
   *
   * Supported parameter types:
   *   - Int
   *   - Boolean
   *
   * Heuristics (higher is better):
   *   - Boolean-looking literals prefer Boolean parameters
   *   - Otherwise, integer literals prefer Int parameters
   */
  private def convertArg(raw: String, targetType: Class[_]): ConvertedArg = {
    val (token, wasQuoted) = stripOptionalQuotes(raw.trim)

    def penalizeIfQuoted(score: Int): Int =
      math.max(0, if (wasQuoted) score - 30 else score)

    def ok(value: Object, baseScore: Int): ConvertedArg =
      ConvertedArg(value, penalizeIfQuoted(baseScore))

    try {
      if (isIntType(targetType)) {
        ok(java.lang.Integer.valueOf(parseIntLiteral(token)), 80)
      } else if (isBooleanType(targetType)) {
        val base = booleanLiteralScore(token)
        ok(java.lang.Boolean.valueOf(parseBooleanLiteral(token)), base)
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

  private def booleanLiteralScore(token: String): Int =
    token.trim.toLowerCase match {
      case "true" | "false" => 90
      case "t" | "f"        => 80
      case "1" | "0"        => 50
      case _                => 0
    }

  private def isIntType(t: Class[_]): Boolean =
    (t == java.lang.Integer.TYPE) || (t == classOf[java.lang.Integer])

  private def isBooleanType(t: Class[_]): Boolean =
    (t == java.lang.Boolean.TYPE) || (t == classOf[java.lang.Boolean])

  private def stripOptionalQuotes(s: String): (String, Boolean) = {
    val t = s.trim
    if (t.length >= 2) {
      val first = t.charAt(0)
      val last = t.charAt(t.length - 1)
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        (unescape(t.substring(1, t.length - 1)), true)
      } else (t, false)
    } else (t, false)
  }

  private def unescape(s: String): String = {
    val out = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '\\' && i + 1 < s.length) {
        s.charAt(i + 1) match {
          case 'n'  => out.append('\n'); i += 2
          case 't'  => out.append('\t'); i += 2
          case 'r'  => out.append('\r'); i += 2
          case 'b'  => out.append('\b'); i += 2
          case 'f'  => out.append('\f'); i += 2
          case '\\' => out.append('\\'); i += 2
          case '"'  => out.append('"'); i += 2
          case '\'' => out.append('\''); i += 2

          case 'u' if i + 5 < s.length =>
            val hex = s.substring(i + 2, i + 6)
            val ok = hex.forall(ch => Character.digit(ch, 16) >= 0)
            if (ok) {
              out.append(Integer.parseInt(hex, 16).toChar)
              i += 6
            } else {
              // Treat an invalid \u escape as a literal 'u'
              out.append('u')
              i += 2
            }

          case other =>
            // Unknown escape: keep the escaped character (drop the backslash)
            out.append(other)
            i += 2
        }
      } else {
        out.append(c)
        i += 1
      }
    }
    out.toString
  }

  private def removeNumericSeparators(s: String): String =
    s.replace("_", "")

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

  private def parseBooleanLiteral(raw: String): Boolean = {
    raw.trim.toLowerCase match {
      case "true" | "t" | "1"  => true
      case "false" | "f" | "0" => false
      case other =>
        throw new NumberFormatException(s"expected boolean literal (true/false/1/0), got '${other}'")
    }
  }
}
