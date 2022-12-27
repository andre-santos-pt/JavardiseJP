import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.21"
    id("project-report")
    application
}

group = "pt.iscte"
version = "0.1"


repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
    implementation("org.junit.platform:junit-platform-suite:1.9.1")

    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.24.8")

    val os = System.getProperty("os.name").toLowerCase()
    if(os.contains("mac")) {
        implementation(files("libs/swt-macos.jar"))
    }
    else if(os.contains("windows")) {
        implementation(files("libs/swt-windows.jar"))
    }
    //implementation (files("libs/javaparser-core-3.24.7.jar"))

}

//configurations.all {
//    resolutionStrategy {
//        dependencySubstitution {
//            val os = System.getProperty("os.name").toLowerCase()
//            val osgi = "org.eclipse.platform:org.eclipse.swt.\${osgi.platform}"
//            if (os.contains("windows")) {
//                substitute(module(osgi))
//                    .using(module("org.eclipse.platform:org.eclipse.swt.win32.win32.x86_64:3.114.0"))
//            }
//            else if (os.contains("linux")) {
//                substitute(module(osgi))
//                    .using(module("org.eclipse.platform:org.eclipse.swt.gtk.linux.x86_64:3.114.0"))
//            }
//            else if (os.contains("mac")) {
//                substitute(module(osgi))
//                    .using(module("org.eclipse.platform:org.eclipse.swt.cocoa.macosx.x86_64:4.6.1"))
//            }
//        }
//    }
//}

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
        dependsOn.addAll(listOf("compileJava", "compileKotlin", "processResources")) // We need this for Gradle optimization to work
        archiveClassifier.set("macos") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
       // manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .filter {!it.name.contains("junit") && !it.name.contains("opentest")}
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }
    val winJar = register<Jar>("winJar") {
        dependsOn.addAll(listOf("compileJava", "compileKotlin", "processResources")) // We need this for Gradle optimization to work
        archiveClassifier.set("windows") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .map { File(it.absolutePath.replace("mac", "win")) }
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }
    val kotlinJar = register<Jar>("kotlinJar") {
        dependsOn.addAll(listOf("compileJava", "compileKotlin", "processResources")) // We need this for Gradle optimization to work
        archiveClassifier.set("kotlin") // Naming the jar
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

      //  manifest { attributes(mapOf("Main-Class" to application.mainClass)) } // Provided we set it up in the application plugin configuration
        val sourcesMain = sourceSets.main.get()
        val contents = configurations.runtimeClasspath.get()
            .filter {it.name != "swt-macos.jar"}
            .map { if (it.isDirectory) it else zipTree(it) } +
                sourcesMain.output
        from(contents)
    }

    build {
        dependsOn(macJar) // Trigger fat jar creation during build
        dependsOn(kotlinJar)
        dependsOn(winJar)
    }
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = application.mainClass
        //attributes["Automatic-Module-Name"] = "pt.iscte.javardise"
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

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
