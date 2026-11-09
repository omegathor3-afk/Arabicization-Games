package com.omega.arabify

internal enum class GameEngine(val displayName: String) {
 UNITY("Unity"), UNREAL("Unreal Engine"), GODOT("Godot"),
 ANDROID("Android Native"), HTML5("HTML5 / Web"), UNKNOWN("غير معروف")
}
internal object EngineDetector {
 fun detect(paths: Collection<String>): GameEngine {
  val p=paths.map { it.lowercase() }
  if(p.any { it.contains("assets/bin/data/managed") || it.endsWith("globalgamemanagers") || it.endsWith("unityplayeractivity") }) return GameEngine.UNITY
  if(p.any { it.contains("ue4game") || it.contains("unrealgame") || it.endsWith(".ucas") || it.endsWith(".utoc") }) return GameEngine.UNREAL
  if(p.any { it.contains("godot") || it.endsWith(".pck") }) return GameEngine.GODOT
  if(p.any { it.endsWith(".html") || it.endsWith(".js") || it.contains("www/") }) return GameEngine.HTML5
  if(p.any { it == "classes.dex" || it.matches(Regex("classes[0-9]+\\.dex")) }) return GameEngine.ANDROID
  return GameEngine.UNKNOWN
 }
}
