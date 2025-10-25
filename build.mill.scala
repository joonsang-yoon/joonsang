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
    mvn"org.chipsalliance::chisel:7.1.1"
  )

  override def scalacPluginMvnDeps = Seq(
    mvn"org.chipsalliance:::chisel-plugin:7.1.1"
  )

  object test extends ScalaTests with ScalaTest with ScalafmtModule {
    override def mvnDeps = super.mvnDeps() ++ Seq(
      mvn"org.scalatest::scalatest:3.2.19"
    )
  }
}

object TopLevelModule extends ChiselModule {
  override def moduleDeps = Seq(ExternalModule, HardFloat)
}

object ExternalModule extends ChiselModule

object HardFloat extends ChiselModule {
  override def moduleDeps = Seq(HardUtils)
}

object HardUtils extends ChiselModule
