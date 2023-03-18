plugins {
    kotlin("jvm") version "1.8.10"
    application
    id("org.panteleyev.jpackageplugin") version "1.5.1"
}

group = "pt.iscte.javardise"
version = "1.0.2"

val mac = System.getProperty("os.name").toLowerCase().contains("mac")
val win = System.getProperty("os.name").toLowerCase().contains("windows")

fun resolutionSwt(
    dependencyResolveDetails: DependencyResolveDetails,
    buildGradle: Build_gradle
) {
    if (dependencyResolveDetails.requested.name.contains("\${osgi.platform}")) {
        val platform = if (buildGradle.mac) "cocoa.macosx.x86_64"
        else if (buildGradle.win) "win32.win32.x86_64"
        else "TODO"
        dependencyResolveDetails.useTarget(
            dependencyResolveDetails.requested.toString()
                .replace("\${osgi.platform}", platform)
        )
    }
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            resolutionSwt(this, this@Build_gradle)
        }
    }
}

subprojects {
    configurations.all {
        resolutionStrategy {
            eachDependency {
                resolutionSwt(this, this@Build_gradle)
            }
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testApi("org.junit.jupiter:junit-jupiter-api:5.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
    api("org.junit.platform:junit-platform-suite:1.9.1")

    api("com.github.javaparser:javaparser-symbol-solver-core:3.24.8")

    if (mac) {
        //api(files("libs/swt-macos.jar"))
        api("org.eclipse.platform:org.eclipse.swt.cocoa.macosx.x86_64:3.123.0")
    } else if (win) {
        //api(files("libs/swt-windows.jar"))
        api("org.eclipse.platform:org.eclipse.swt.win32.win32.x86_64:3.123.0")
    }
}

application {
    mainClass.set("pt.iscte.javardise.editor.MainKt")
    applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
}

tasks.compileJava {
    // use the project's version or define one directly
    //options.javaModuleVersion.set(provider { project.version as String })
}

tasks {
    val macJar = register<Jar>("macJar") {
        dependsOn.addAll(
            listOf(
                "compileJava",
                "compileKotlin",
                "processResources"
            )
        ) // We need this for Gradle optimization to work
        archiveClassifier.set("macos") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .filter { !it.name.contains("junit") && !it.name.contains("opentest") }
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }
    val winJar = register<Jar>("winJar") {
        dependsOn.addAll(
            listOf(
                "compileJava",
                "compileKotlin",
                "processResources"
            )
        ) // We need this for Gradle optimization to work
        archiveClassifier.set("windows") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .map { File(it.absolutePath.replace("macos", "windows")) }
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }

    build {
        //dependsOn(macJar) // Trigger fat jar creation during build
        // dependsOn(winJar)
    }
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = application.mainClass
        //attributes["Automatic-Module-Name"] = "pt.iscte.javardise"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    //from(configurations.runtimeClasspath)
    from(sourceSets.main.get().output) {
        include("resources/**")
        into("resources")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf("-XstartOnFirstThread")
}

task("copyDependencies", Copy::class) {
    from(configurations.runtimeClasspath).into("$buildDir/jars")
}

task("copyJar", Copy::class) {
    from(tasks.jar).into("$buildDir/jars")
}

task("copyPlugins", Copy::class) {
    from(
        File(
            project.project("documentation").buildDir,
            "libs/documentation.jar"
        )
    ).into("$buildDir/jars")
    from(
        File(
            project.project("compilation").buildDir,
            "libs/compilation.jar"
        )
    ).into("$buildDir/jars")
}

tasks.jpackage {
    dependsOn("copyDependencies", "copyJar", "copyPlugins")

    input = "$buildDir/jars"
    destination = "$buildDir/dist"

    appName = "Paddle"
    vendor = "pt.iscte"

    mainJar = tasks.jar.get().archiveFileName.get()
    mainClass = application.mainClass.get()

    javaOptions = listOf("-Dfile.encoding=UTF-8", "-XstartOnFirstThread")

    mac {
        // Generic parameter value for OS X build
        //icon = "icon.icns"
    }

    windows {
        winConsole = true
    }
}

