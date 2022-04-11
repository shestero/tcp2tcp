name := "six2tcp"

version := "0.2"

scalaVersion := "2.12.10"


libraryDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, major)) if major >= 13 =>
      Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0")
    case _ =>
      Seq()
  }
}

lazy val akkaver =  "2.5.23" // "2.4.14" // "2.5.23" // "2.4.18"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"   % akkaver,
  "com.typesafe.akka" %% "akka-stream"  % akkaver,
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

// resolvers += "softprops-maven" at "http://dl.bintray.com/content/softprops/maven"
resolvers += Resolver.bintrayRepo("softprops", "maven")
libraryDependencies += "com.github.karasiq" % "proxyutils_2.12" % "2.0.14" // ?

mainClass in (Compile, run) := Some("Main")

resolvers += Resolver.url("bintray-sbt-plugins", url("http://dl.bintray.com/sbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)
/*
assemblyMergeStrategy in assembly := {
  //case PathList("org", "slf4j", xs@_*) => MergeStrategy.first
  //case x => (assemblyMergeStrategy in assembly).value(x)
  //case x if x.contains("asm") => MergeStrategy.first
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}
*/
/*
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}
 */

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case x => MergeStrategy.first
}

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")
enablePlugins(AssemblyPlugin)
//unmanagedJars ++= Seq()
