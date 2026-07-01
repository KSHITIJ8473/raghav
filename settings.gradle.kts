rootProject.name = "CloudstreamPlugins"

val disabled = listOf<String>("AnimetsuPlugin", "ExampleProvider", "Anizen","LivXowPlugin", "AniDoorPlugin", "PpvPlugin" )

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
  
