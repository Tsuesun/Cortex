name := """cortex"""

lazy val cortex = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(PublishToBinTray)

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  ehcache,
  ws,
  specs2 % Test,
  "net.codingwell" %% "scala-guice" % "4.1.0",
  "org.cert-bdf" %% "elastic4play" % "1.5.0",
  "org.reflections" % "reflections" % "0.9.11",
  "net.lingala.zip4j" % "zip4j" % "1.3.2",
  "com.typesafe.play" %% "play-guice" % play.core.PlayVersion.current
)

// Add information in manifest
import Package.ManifestAttributes
import java.util.jar.Attributes.Name._
packageOptions  ++= Seq(
  ManifestAttributes(IMPLEMENTATION_TITLE -> name.value),
  ManifestAttributes(IMPLEMENTATION_VERSION -> version.value),
  ManifestAttributes(SPECIFICATION_VENDOR -> "TheHive Project"),
  ManifestAttributes(SPECIFICATION_TITLE -> name.value),
  ManifestAttributes(SPECIFICATION_VERSION -> "TheHive Project")
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
publishArtifact in (Compile, packageDoc) := false
publishArtifact in packageDoc := false
sources in (Compile,doc) := Seq.empty

// Front-end //
mappings in packageBin in Assets ++= frontendFiles.value

// Install files //
mappings in Universal ~= {
  _.flatMap {
    case (_, "conf/application.conf") => Nil
    case (file, "conf/apllication.sample") => Seq(file -> "conf/application.conf")
    case (_, "conf/logback.xml") => Nil
    case other => Seq(other)
  } ++ Seq(
    file("package/cortex.service") -> "package/cortex.service",
    file("package/cortex.conf") -> "package/cortex.conf",
    file("package/cortex") -> "package/cortex",
    file("package/logback.xml") -> "conf/logback.xml"
  )
}

// Package //
maintainer := "TheHive Project <support@thehive-project.org>"
packageSummary := "Powerful Observable Analysis Engine"
packageDescription := """Cortex tries to solve a common problem frequently encountered by SOCs, CSIRTs and security
  | researchers in the course of threat intelligence, digital forensics and incident response: how to analyze
  | observables they have collected, at scale, by querying a single tool instead of several?
  | Cortex, an open source and free software, has been created by TheHive Project for this very purpose. Observables,
  | such as IP and email addresses, URLs, domain names, files or hashes, can be analyzed one by one or in bulk mode
  | using a Web interface. Analysts can also automate these operations thanks to the Cortex REST API. """.stripMargin
defaultLinuxInstallLocation := "/opt"
linuxPackageMappings ~= { _.map { pm =>
  val mappings = pm.mappings.filterNot {
    case (_, path) => path.startsWith("/opt/cortex/package") || (path.startsWith("/opt/cortex/conf") && path != "/opt/cortex/conf/reference.conf")
  }
  com.typesafe.sbt.packager.linux.LinuxPackageMapping(mappings, pm.fileData).withConfig()
} :+ packageMapping(
  file("package/cortex.service") -> "/etc/systemd/system/cortex.service",
  file("package/cortex.conf") -> "/etc/init/cortex.conf",
  file("package/cortex") -> "/etc/init.d/cortex",
  file("conf/application.sample") -> "/etc/cortex/application.conf",
  file("package/logback.xml") -> "/etc/cortex/logback.xml"
).withConfig()
}

packageBin := {
  (packageBin in Debian).value
  (packageBin in Rpm).value
  (packageBin in Universal).value
}
// DEB //
version in Debian := version.value + "-1"
debianPackageDependencies += "openjdk-8-jre-headless"
maintainerScripts in Debian := maintainerScriptsFromDirectory(
  baseDirectory.value / "package" / "debian",
  Seq(DebianConstants.Postinst, DebianConstants.Prerm, DebianConstants.Postrm)
)
linuxEtcDefaultTemplate in Debian := (baseDirectory.value / "package" / "etc_default_cortex").asURL
linuxMakeStartScript in Debian := None

// RPM //
rpmRelease := "1"
rpmVendor := "TheHive Project"
rpmUrl := Some("http://thehive-project.org/")
rpmLicense := Some("AGPL")
rpmRequirements += "java-1.8.0-openjdk-headless"
maintainerScripts in Rpm := maintainerScriptsFromDirectory(
  baseDirectory.value / "package" / "rpm",
  Seq(RpmConstants.Pre, RpmConstants.Preun, RpmConstants.Postun)
)
linuxPackageSymlinks in Rpm := Nil
rpmPrefix := Some(defaultLinuxInstallLocation.value)
linuxEtcDefaultTemplate in Rpm := (baseDirectory.value / "package" / "etc_default_cortex").asURL
packageBin in Rpm := {
  import scala.sys.process._

  val rpmFile = (packageBin in Rpm).value
  s"rpm --addsign $rpmFile".!!
  rpmFile
}

// DOCKER //
import com.typesafe.sbt.packager.docker.{ Cmd, ExecCmd }

version in Docker := version.value + "-1"
defaultLinuxInstallLocation in Docker := "/opt/cortex"
dockerRepository := Some("certbdf")
dockerUpdateLatest := true
dockerEntrypoint := Seq("/opt/cortex/entrypoint")
dockerExposedPorts := Seq(9001)
mappings in Docker ++= Seq(
  file("package/docker/entrypoint") -> "/opt/cortex/entrypoint",
  file("conf/logback.xml") -> "/etc/cortex/logback.xml",
  file("package/empty") -> "/var/log/cortex/application.log")
mappings in Docker ~= (_.filterNot {
  case (_, filepath) => filepath == "/opt/cortex/conf/application.conf"
})

dockerCommands ~= { dc =>
  val (dockerInitCmds, dockerTailCmds) = dc
    .collect {
      case ExecCmd("RUN", "chown", _*) => ExecCmd("RUN", "chown", "-R", "daemon:root", ".")
      case other => other
    }
    .splitAt(4)
  dockerInitCmds ++
    Seq(
      Cmd("USER", "root"),
      ExecCmd("RUN", "bash", "-c",
        "apt-get update && " +
          "apt-get install -y --no-install-recommends python-pip python2.7-dev python3-pip python3-dev ssdeep libfuzzy-dev libfuzzy2 libimage-exiftool-perl libmagic1 build-essential git libssl-dev && " +
          "pip install -U pip setuptools && " +
          "pip3 install -U pip setuptools && " +
          "cd /opt && " +
          "git clone https://github.com/CERT-BDF/Cortex-Analyzers.git && " +
          "for I in Cortex-Analyzers/analyzers/*/requirements.txt; do pip2 install -r $I; done && " +
          "for I in Cortex-Analyzers/analyzers/*/requirements.txt; do pip3 install -r $I || true; done"),
      Cmd("ADD", "var", "/var"),
      Cmd("ADD", "etc", "/etc"),
      ExecCmd("RUN", "chown", "-R", "daemon:root", "/var/log/cortex"),
      ExecCmd("RUN", "chmod", "+x", "/opt/cortex/bin/cortex", "/opt/cortex/entrypoint")) ++
    dockerTailCmds
}

// Bintray //
bintrayOrganization := Some("cert-bdf")
bintrayRepository := "cortex"
publish := {
  (publish in Docker).value
  publishRelease.value
  publishLatest.value
  publishRpm.value
  publishDebian.value
}
