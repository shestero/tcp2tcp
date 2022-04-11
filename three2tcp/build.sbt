name := "three2tcp"

version := "0.1"

scalaVersion := "2.12.9"


libraryDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, major)) if major >= 13 =>
      Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0")
    case _ =>
      Seq()
  }
}

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"   % "2.5.+", // current: 2.5.23
  "org.scodec"        %% "scodec-core"  % "1.11.+" // current: 1.11.4
  //  "org.scodec"        %% "scodec-akka"  % "0.3.+"   // current: 0.3.0
)

libraryDependencies ++= {
  if (scalaBinaryVersion.value startsWith "2.10")
    Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full))
  else Nil
}

// https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk15on
libraryDependencies += "org.bouncycastle" % "bcprov-jdk15on" % "1.62"

mainClass in (Compile, run) := Some("Main")

enablePlugins(AssemblyPlugin)
//unmanagedJars ++= Seq()
