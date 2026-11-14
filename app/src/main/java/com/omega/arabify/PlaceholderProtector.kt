package com.omega.arabify

internal data class ProtectedText(val text: String, val tokens: List<String>)

internal object PlaceholderProtector {
 private val tokenPattern = Regex("%(?:[0-9]+\\$)?[sdif]|\\$\\{[^}]+\\}|\\{[^{}]+\\}|<[^>]+>|\\\\[nt]")

 fun protect(source: String): ProtectedText {
  val tokens = mutableListOf<String>()
  val protected = tokenPattern.replace(source) { match ->
   val index = tokens.size
   tokens += match.value
   "__ARABIFY_TOKEN_${index}__"
  }
  return ProtectedText(protected, tokens)
 }

 fun restore(translated: String, tokens: List<String>): String {
  var result = translated
  tokens.forEachIndexed { index, token ->
   result = result.replace("__ARABIFY_TOKEN_${index}__", token)
  }
  return result
 }
}
