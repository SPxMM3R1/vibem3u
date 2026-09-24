plugins {
    application
}

val appSources = file("../app/src/main/java")
val resolverFiles = listOf(
    "AppStrings.java",
    "Channel.java",
    "ChannelRequestHeaders.java",
    "DynamicSourceReference.java",
    "HlsCandidateRace.java",
    "HlsStreamValidator.java",
    "HighflyCatalogChannel.java",
    "HighflyHlsDecoder.java",
    "HighflyStreamResolver.java",
    "M3uParser.java",
    "ManifestHandoffCache.java",
    "ManifestHandoffData.java",
    "MeganoticiasHlsDecoder.java",
    "MeganoticiasStreamResolver.java",
    "Playlist.java",
    "PublicStreamPolicy.java",
    "ProviderStreamParsers.java",
    "ResolutionContext.java",
    "ResolutionDeadline.java",
    "ResolutionProgress.java",
    "ResolutionProgressListener.java",
    "ResolutionStage.java",
    "ResolverCatalog.java",
    "ResolverDefinition.java",
    "ResolverPayloadParsers.java",
    "ResolvedPlaybackCandidate.java",
    "ResolvedPlaybackSource.java",
    "SafePlaybackText.java",
    "SharedHttpClient.java",
    "StreamResolver.java",
    "TokenExpiryPolicy.java",
    "TokenHttpClient.java",
    "TvnStreamResolver.java",
    "TvVooCatalogChannel.java",
    "TvVooSourceHistory.java",
    "TvVooStreamResolver.java",
    "VavooStreamResolver.java",
    "VavooSessionClient.java",
)

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir(appSources)
            include("cl/streambox/tv/local/**")
            resolverFiles.forEach { include("cl/streambox/tv/$it") }
        }
    }
    test {
        java {
            srcDir("src/test/java")
        }
    }
}

application {
    mainClass.set("cl.streambox.tv.local.LocalCatalogServer")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    // The shared TvVoo alias-history type has Android signatures. This module
    // never creates a context-backed store, so its Android APIs are not used.
    compileOnly("com.google.android:android:4.1.1.4")
    runtimeOnly("com.google.android:android:4.1.1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.named<JavaExec>("run") {
    val webRoot = providers.gradleProperty("catalogWebRoot").orElse("")
    val listaRoot = providers.gradleProperty("catalogListaRoot").orElse("")
    val vibeRoot = providers.gradleProperty("catalogVibeRoot").orElse("")
    val port = providers.gradleProperty("catalogPort").orElse("8787")
    args(
        "--web-root", webRoot.get(),
        "--lista-root", listaRoot.get(),
        "--vibe-root", vibeRoot.get(),
        "--port", port.get(),
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
