package com.omega.arabify
import org.junit.Assert.assertEquals
import org.junit.Test
class EngineDetectorTest {
 @Test fun unity()=assertEquals(GameEngine.UNITY,EngineDetector.detect(listOf("assets/bin/Data/Managed/Assembly-CSharp.dll")))
 @Test fun unreal()=assertEquals(GameEngine.UNREAL,EngineDetector.detect(listOf("assets/Paks/game.pak","assets/game.utoc")))
 @Test fun unrealNativeLibrary()=assertEquals(GameEngine.UNREAL,EngineDetector.detect(listOf("lib/arm64-v8a/libUE4.so")))
 @Test fun unityIl2Cpp()=assertEquals(GameEngine.UNITY,EngineDetector.detect(listOf("lib/arm64-v8a/libil2cpp.so")))
 @Test fun godot()=assertEquals(GameEngine.GODOT,EngineDetector.detect(listOf("assets/game.pck")))
 @Test fun androidDex()=assertEquals(GameEngine.ANDROID,EngineDetector.detect(listOf("classes.dex")))
 @Test fun unknown()=assertEquals(GameEngine.UNKNOWN,EngineDetector.detect(listOf("assets/image.png")))
}
