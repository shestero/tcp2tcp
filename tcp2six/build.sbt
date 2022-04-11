name := "tcp2six"

version := "0.2"

scalaVersion := "2.12.8"

val akka = "2.5.27" //"2.4.18"

libraryDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, major)) if major >= 13 =>
      Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0")
    case _ =>
      Seq()
  }
}

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"   % akka, // current: 2.5.23
  "org.scodec"        %% "scodec-core"  % "1.11.+", // current: 1.11.4
  //  "org.scodec"        %% "scodec-akka"  % "0.3.+"   // current: 0.3.0
  "com.typesafe.akka" %% "akka-stream"  % akka, // "2.5.24"
  "com.typesafe.akka" %% "akka-http"    % "10.1.11"
)

libraryDependencies += "org.typelevel" %% "cats-core" % "2.0.0"

// https://github.com/MfgLabs/akka-stream-extensions
//resolvers += Resolver.bintrayRepo("mfglabs", "maven")
//libraryDependencies += "com.mfglabs" %% "akka-stream-extensions" % "0.11.2"
//Currently depends on akka-stream-2.4.18


libraryDependencies ++= {
  if (scalaBinaryVersion.value startsWith "2.10")
    Seq(compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full))
  else Nil
}

// https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk15on
libraryDependencies += "org.bouncycastle" % "bcprov-jdk15on" % "1.62"

mainClass in (Compile, run) := Some("Main")

resolvers += Resolver.url("bintray-sbt-plugins", url("http://dl.bintray.com/sbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case x => MergeStrategy.first
}
enablePlugins(AssemblyPlugin)
//unmanagedJars ++= Seq()
