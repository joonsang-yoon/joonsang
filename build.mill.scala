import mill._
import mill.scalalib.scalafmt.ScalafmtModule
import mill.scalalib.TestModule.ScalaTest
import mill.scalalib._

trait ChiselModule extends ScalaModule with ScalafmtModule {
  override def scalaVersion = "2.13.16"

  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )

  override def mvnDeps = Seq(
    mvn"org.chipsalliance::chisel:7.5.0"
  )

  override def scalacPluginMvnDeps = Seq(
    mvn"org.chipsalliance:::chisel-plugin:7.5.0"
  )

  object test extends ScalaTests with ScalaTest with ScalafmtModule {
    override def mvnDeps = super.mvnDeps() ++ Seq(
      mvn"org.scalatest::scalatest:3.2.19",
      mvn"org.scala-lang.modules::scala-parallel-collections:1.2.0"
    )
  }
}

object TopLevelModule extends ChiselModule {
  override def moduleDeps = Seq(ExternalModule, HardFloat, HardInt)
}

object ExternalModule extends ChiselModule

object HardFloat extends ChiselModule {
  override def moduleDeps = Seq(HardUtils)
}

object HardInt extends ChiselModule {
  override def moduleDeps = Seq(HardUtils, RocketChip)
}

object HardUtils extends ChiselModule

object RocketChip extends ChiselModule {
  override def moduleDir = super.moduleDir / os.up / "rocket-chip"
  override def sources = Task.Sources(
    moduleDir / "src" / "main" / "scala"
  )
  override def moduleDeps = Seq(Macros, BerkeleyHardfloat, CDE, Diplomacy)
  override def mvnDeps = super.mvnDeps() ++ Seq(
    mvn"com.lihaoyi::mainargs:0.5.0",
    mvn"org.json4s::json4s-jackson:4.0.5"
  )
}

object Macros extends ChiselModule {
  override def moduleDir = RocketChip.moduleDir / "macros"
  override def sources = Task.Sources(
    moduleDir / "src" / "main" / "scala"
  )
  override def mvnDeps = super.mvnDeps() ++ Seq(
    mvn"org.scala-lang:scala-reflect:${scalaVersion}"
  )
}

object BerkeleyHardfloat extends ChiselModule {
  override def moduleDir = RocketChip.moduleDir / "dependencies" / "hardfloat" / "hardfloat"
  override def sources = Task.Sources(
    moduleDir / "src" / "main" / "scala"
  )
}

object CDE extends ChiselModule {
  override def moduleDir = RocketChip.moduleDir / "dependencies" / "cde" / "cde"
}

object Diplomacy extends ChiselModule {
  override def moduleDir = RocketChip.moduleDir / "dependencies" / "diplomacy" / "diplomacy"
  override def moduleDeps = Seq(CDE)
  override def mvnDeps = super.mvnDeps() ++ Seq(
    mvn"com.lihaoyi::sourcecode:0.3.1"
  )
}
