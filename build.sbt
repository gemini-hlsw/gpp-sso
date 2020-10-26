import sbtcrossproject.CrossType

inThisBuild(Seq(
  homepage := Some(url("https://github.com/gemini-hlsw/lucuma-sso")),
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
  libraryDependencies ++= Seq(
    "com.disneystreaming" %% "weaver-framework"  % "0.5.0" % Test,
    "com.disneystreaming" %% "weaver-scalacheck" % "0.5.0" % Test,
  ),
  testFrameworks += new TestFramework("weaver.framework.TestFramework"),
) ++ lucumaPublishSettings)

skip in publish := true

lazy val frontendClient = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/frontend-client"))
  .settings(
    name := "lucuma-sso-frontend-client",
    libraryDependencies ++= Seq(
      "edu.gemini"    %%% "lucuma-core"         % "0.6.1",
      "io.circe"      %%% "circe-generic"       % "0.13.0",
      "edu.gemini"    %%% "lucuma-core-testkit" % "0.6.1"  % Test,
      "org.scalameta" %%% "munit"               % "0.7.14" % Test,
      "org.scalameta" %%% "munit-scalacheck"    % "0.7.14" % Test,
      "org.typelevel" %%% "discipline-munit"    % "0.3.0"  % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val backendClient = project
  .in(file("modules/backend-client"))
  .dependsOn(frontendClient.jvm)
  .settings(
    name := "lucuma-sso-backend-client",
    libraryDependencies ++= Seq(
      "com.pauldijou"     %% "jwt-circe"      % "4.3.0",
      "com.pauldijou"     %% "jwt-core"       % "4.3.0",
      "org.bouncycastle"  %  "bcpg-jdk15on"   % "1.66",
      "org.http4s"        %% "http4s-circe"   % "0.21.8",
      "org.http4s"        %% "http4s-circe"   % "0.21.8",
      "org.http4s"        %% "http4s-dsl"     % "0.21.8",
      "io.chrisdavenport" %% "log4cats-slf4j" % "1.1.1",
    ),
  )

lazy val service = project
  .in(file("modules/service"))
  .dependsOn(backendClient)
  .enablePlugins(JavaAppPackaging)
  .settings(
    publish / skip := true,
    name := "lucuma-sso-service",
    libraryDependencies ++= Seq(
      "io.circe"       %% "circe-parser"        % "0.13.0",
      "is.cir"         %% "ciris"               % "1.2.1",
      "org.http4s"     %% "http4s-ember-client" % "0.21.8",
      "org.http4s"     %% "http4s-ember-server" % "0.21.8",
      "org.http4s"     %% "http4s-scala-xml"    % "0.21.8",
      "org.slf4j"      %  "slf4j-simple"        % "1.7.30",
      "org.tpolecat"   %% "natchez-jaeger"      % "0.0.13",
      "org.tpolecat"   %% "skunk-core"          % "0.0.21",
      "org.flywaydb"   % "flyway-core"          % "6.5.7",
      "org.postgresql" % "postgresql"           % "42.2.18",
    ),
  )

lazy val backendExample = project
  .in(file("modules/backend-example"))
  .dependsOn(backendClient)
  .settings(
    publish / skip := true,
    name := "lucuma-sso-backend-example",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % "0.21.8",
      "org.http4s" %% "http4s-ember-server" % "0.21.8",
      "org.slf4j"  %  "slf4j-simple"        % "1.7.30",
    )
  )