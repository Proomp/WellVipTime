plugins {
    java
    checkstyle
    id("com.gradleup.shadow") version "9.3.1"
}

group = "com.wellsetups.wellviptime"
version = "2.1.1-dev"

val databaseLibraries = listOf(
    "com.zaxxer:HikariCP:6.3.0",
    "org.xerial:sqlite-jdbc:3.49.1.0",
    "org.mariadb.jdbc:mariadb-java-client:3.5.3",
    "net.kyori:adventure-api:4.17.0",
    "net.kyori:adventure-text-minimessage:4.17.0",
    "net.kyori:adventure-text-serializer-legacy:4.17.0",
    "net.kyori:adventure-text-serializer-plain:4.17.0",
    "net.kyori:adventure-platform-bukkit:4.3.4"
)

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}
dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")
    databaseLibraries.forEach {
        compileOnly(it)
        testImplementation(it)
    }
    testImplementation("net.luckperms:api:5.4")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)); withSourcesJar() }
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}
tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.compileTestJava { options.release.set(25) }
val generateLibraryList by tasks.registering {
    val libraryContents = databaseLibraries.joinToString("\n", postfix = "\n")
    inputs.property("libraries", libraryContents)
    val destination = layout.buildDirectory.file("generated/library-resources/libraries.list")
    outputs.file(destination)
    doLast {
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(libraryContents)
        }
    }
}
tasks.processResources {
    from(generateLibraryList) { into("META-INF/vipmanager") }
    val resourceProperties = mapOf("version" to project.version.toString(), "libraries" to databaseLibraries.joinToString("\n") { "  - " + it })
    inputs.properties(resourceProperties)
    filesMatching("plugin.yml") { expand(resourceProperties) }
}
tasks.jar { archiveClassifier.set("thin") }
tasks.shadowJar {
    archiveClassifier.set("")
    dependencies { exclude { it.moduleGroup != "org.bstats" } }
    relocate("org.bstats", "com.wellsetups.wellviptime.internal.bstats")
}
tasks.build { dependsOn(tasks.shadowJar) }
dependencyLocking { lockAllConfigurations() }

checkstyle {
    toolVersion = "10.26.1"
    isIgnoreFailures = false
    maxWarnings = 0
}
val javaFormatter by configurations.creating
dependencies { javaFormatter("com.google.googlejavaformat:google-java-format:1.28.0:all-deps") { isTransitive = false } }
val javaSources = fileTree("src") { include("**/*.java") }
tasks.register<JavaExec>("formatJava") {
    group = "formatting"
    description = "Apply the project's four-space Java formatting."
    classpath = javaFormatter
    mainClass.set("com.google.googlejavaformat.java.Main")
    jvmArgs(listOf("api", "file", "parser", "tree", "util", "code").map {
        "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
    })
    args("--aosp", "--replace")
    args(javaSources.files.sorted().map { it.absolutePath })
}
val checkJavaFormat by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Reject Java formatting changes; does not modify source."
    classpath = javaFormatter
    mainClass.set("com.google.googlejavaformat.java.Main")
    jvmArgs(listOf("api", "file", "parser", "tree", "util", "code").map {
        "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
    })
    args("--aosp", "--dry-run", "--set-exit-if-changed")
    args(javaSources.files.sorted().map { it.absolutePath })
}
tasks.check { dependsOn(checkJavaFormat) }

val writeAnalysisClasspath by tasks.registering {
    group = "verification"
    description = "Write the compile classpath for external PMD analysis."
    dependsOn(tasks.classes, tasks.testClasses)
    val analysisFiles = sourceSets.main.get().compileClasspath + sourceSets.main.get().output.classesDirs +
        sourceSets.test.get().compileClasspath + sourceSets.test.get().output.classesDirs
    val destination = layout.buildDirectory.file("reports/quality/auxclasspath.txt")
    inputs.files(analysisFiles)
    outputs.file(destination)
    doLast {
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(analysisFiles.asPath)
        }
    }
}
