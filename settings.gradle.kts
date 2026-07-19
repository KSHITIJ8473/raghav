rootProject.name = "CloudstreamPlugins"

// Toolchain auto-provisioning: lets Gradle download Java 17 if not installed locally
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val disabled = listOf<String>("Anizen", "Reanimate", "LivXow", "AniDoorPlugin", "AnimeShrineDownloader", "PlayFyProvider", "LunarAnime", "AnimoTvSlash")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
  
