name := "four2tcp"

version := "0.4"

scalaVersion := "2.12.7"

// PartitionHub
// need akka 2.5.4 ??

libraryDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, major)) if major >= 13 =>
      Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0")
    case _ =>
      Seq()
  }
}

lazy val akkaver = "2.4.18"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"   % akkaver, // current: 2.5.23
  "org.scodec"        %% "scodec-core"  % "1.11.+" // current: 1.11.4
  //  "org.scodec"        %% "scodec-akka"  % "0.3.+"   // current: 0.3.0
)

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaver
//libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.24"
//libraryDependencies += "com.typesafe.akka" %% "akka-stream-typed" % akkaver // ActorSink

// https://github.com/MfgLabs/akka-stream-extensions
resolvers += Resolver.bintrayRepo("mfglabs", "maven")
libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.11.2"
//Currently depends on akka-stream-2.4.18

// depends on akka 2.5.4 ?
//libraryDependencies += "com.github.karasiq" %% "cryptoutils" % "1.4.3"
//libraryDependencies += "com.github.karasiq" %% "proxyutils" % "2.0.13"
//libraryDependencies += "com.github.karasiq" %% "coffeescript" % "1.0.2"


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
