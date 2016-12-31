
name := "kamon-logstash"

lazy val buildSettings = Seq(
  organization := "darienmt",
  scalaVersion := "2.11.8"
)

lazy val compilerOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:postfixOps",
  "-unchecked",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Xfuture"
)

// Libraries
lazy val akkaVersion = "2.4.12"

lazy val akkaLib = Seq(
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion
)

lazy val logbackVersion = "1.1.6"
lazy val jacksonVersion = "2.6.5"

lazy val loggingLib = Seq(
  "ch.qos.logback" % "logback-core" % logbackVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "net.logstash.logback" % "logstash-logback-encoder" % "4.8",
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion
)


lazy val kamonLibs = Seq(
  "io.kamon" %% "kamon-core",
  "io.kamon" %% "kamon-akka",
  "io.kamon" %% "kamon-log-reporter",
  "io.kamon" %% "kamon-system-metrics"
).map(_ % "0.6.3")

lazy val circeLib = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-java8"
).map(_ % "0.6.0")

lazy val aspectJWeaverVersions = "1.8.9"
lazy val aspectJWeaverLib = Seq(
  "org.aspectj" % "aspectjweaver" % aspectJWeaverVersions
)

lazy val commonLibraries = Seq(
)

lazy val commonSettings = buildSettings ++ commonLibraries ++
  (scalacOptions ++= compilerOptions) ++
  Seq(
    scalastyleFailOnError := true,
    fork in run := true
  )

// Projects
lazy val root = project.in(file("."))
  .aggregate(actorsToMonitor, kamonLogstash)

lazy val actorsToMonitor = project.in(file("modules/actors-to-monitor"))
  .settings(commonSettings:_*)
  .settings(libraryDependencies ++= akkaLib ++ loggingLib ++ kamonLibs)
  .aggregate(kamonLogstash)
  .dependsOn(kamonLogstash)
  .enablePlugins(sbtdocker.DockerPlugin, JavaServerAppPackaging)
  .settings(dockerSettings ++ aspectjSettings)
  .settings(
    mainClass in Compile := Some("FaultHandlingDocSample"),
    javaOptions in run <++= AspectjKeys.weaverOptions in Aspectj
  )

lazy val kamonLogstash = project.in(file("modules/kamon-logstash"))
  .settings(commonSettings:_*)
  .settings(libraryDependencies ++= akkaLib ++ loggingLib ++ kamonLibs ++ circeLib)

// Docker
addCommandAlias("dockerize", ";clean;compile;test;actorsToMonitor/docker")

lazy val dockerSettings = Seq(
  dockerfile in docker := {
    val appDir: File = stage.value
    val targetDir = "/app"
    new Dockerfile {
      from("anapsix/alpine-java:8")
      env("JAVA_OPTS", "-javaagent:/app/lib/org.aspectj.aspectjweaver-" + aspectJWeaverVersions + ".jar" )
      entryPoint(s"$targetDir/bin/${executableScriptName.value}")
      copy(appDir, targetDir)
    }
  },
  imageNames in docker := Seq(
    // Sets the latest tag
    ImageName(s"${organization.value}/${name.value.toLowerCase}:latest"),

    // Sets a name with a tag that contains the project version
    ImageName(
      repository = s"${organization.value}/${name.value.toLowerCase}",
      tag = Some("v" + version.value)
    )
  )
)