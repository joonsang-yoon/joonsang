import mill._
import mill.scalalib.scalafmt.ScalafmtModule
import mill.scalalib.TestModule.ScalaTest
import mill.scalalib._

trait ChiselModule extends ScalaModule with ScalafmtModule { m =>
  override def scalaVersion = "2.13.16"

  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )

  override def ivyDeps = Agg(
    ivy"org.chipsalliance::chisel:7.0.0-RC1"
  )

  override def scalacPluginIvyDeps = Agg(
    ivy"org.chipsalliance:::chisel-plugin:7.0.0-RC1"
  )

  object test extends ScalaTests with TestModule.ScalaTest with ScalafmtModule {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.scalatest::scalatest:3.2.19",
      ivy"edu.berkeley.cs::chiseltest:6.0.0"
    )
  }
}

object TopLevelModule extends ChiselModule { m =>
  override def moduleDeps = Seq(ExternalModule, HardFloat)
}

object ExternalModule extends ChiselModule

object HardFloat extends ChiselModule { m =>
  override def moduleDeps = Seq(HardUtils)
}

object HardUtils extends ChiselModule
