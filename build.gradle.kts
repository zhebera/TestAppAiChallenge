plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "1.9.23"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "2.3.11"
    val exposedVersion = "0.46.0"

    testImplementation(kotlin("test"))

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    //toon
    implementation("dev.toonformat:jtoon:1.0.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    // Exposed (SQLite ORM)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
}

application {
    mainClass.set("org.example.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED"  // Для SQLite JDBC на Java 21+
    )
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Task to run the Wikipedia MCP Server standalone
tasks.register<JavaExec>("runWikipediaMcp") {
    group = "mcp"
    description = "Run the Wikipedia MCP Server"
    mainClass.set("org.example.mcp.server.wikipedia.WikipediaMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Task to run the Summarizer MCP Server standalone
tasks.register<JavaExec>("runSummarizerMcp") {
    group = "mcp"
    description = "Run the Summarizer MCP Server"
    mainClass.set("org.example.mcp.server.summarizer.SummarizerMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Task to run the FileStorage MCP Server standalone
tasks.register<JavaExec>("runFileStorageMcp") {
    group = "mcp"
    description = "Run the FileStorage MCP Server"
    mainClass.set("org.example.mcp.server.filestorage.FileStorageMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Task to run the Android Emulator MCP Server standalone
tasks.register<JavaExec>("runAndroidEmulatorMcp") {
    group = "mcp"
    description = "Run the Android Emulator MCP Server"
    mainClass.set("org.example.mcp.server.android.AndroidEmulatorMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "/Users/andrei/Library/Android/sdk")
}

// Task to run the GitHub Extended MCP Server (git_push, create_pull_request, etc.)
tasks.register<JavaExec>("runGitHubMcp") {
    group = "mcp"
    description = "Run the GitHub Extended MCP Server (git_push, create_pr, etc.)"
    mainClass.set("org.example.mcp.server.github.GitHubMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    // Pass GitHub token from environment
    environment("GITHUB_TOKEN", System.getenv("GITHUB_TOKEN") ?: "")
    environment("GITHUB_PERSONAL_ACCESS_TOKEN", System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN") ?: "")
}

// Task to run PR Review from CI
// Usage: ./gradlew runPrReview --args="owner repo prNumber [options]"
tasks.register<JavaExec>("runPrReview") {
    group = "review"
    description = "Run AI PR Review. Usage: ./gradlew runPrReview --args='owner repo prNumber'"
    mainClass.set("org.example.prreview.PrReviewRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardOutput = System.out
    errorOutput = System.err
}