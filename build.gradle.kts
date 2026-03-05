plugins {
  java
  application
}

group = "org.zenframework.z8.converter"
version = "1.4.1"

repositories {
  mavenCentral()
}

dependencies {
  implementation("info.picocli:picocli:4.7.7")
  implementation("org.jodconverter:jodconverter-local:4.4.11")
  implementation(files(project.property("x2t.docbuilder.lib")!!))
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

application {
  mainClassName = "org.zenframework.z8.converter.Main"
}
