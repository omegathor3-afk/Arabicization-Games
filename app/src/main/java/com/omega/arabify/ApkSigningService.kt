package com.omega.arabify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

internal object ApkSigningService {
 private const val KEY_ALIAS = "arabify_local_apk_signing_v1"

 fun signAndVerify(context: Context, unsignedApk: File, signedApk: File) {
  require(unsignedApk.isFile) { "ملف APK غير الموقع غير موجود." }
  val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  if (!keyStore.containsAlias(KEY_ALIAS)) generateSigningKey()
  val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
   ?: error("تعذر الوصول إلى مفتاح توقيع ARABIFY.")
  val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
   ?: error("تعذر قراءة شهادة توقيع ARABIFY.")
  val signerConfig = ApkSigner.SignerConfig.Builder("ARABIFY", privateKey, listOf(certificate)).build()
  ApkSigner.Builder(listOf(signerConfig))
   .setInputApk(unsignedApk)
   .setOutputApk(signedApk)
   .setV1SigningEnabled(true)
   .setV2SigningEnabled(true)
   .setV3SigningEnabled(true)
   .build()
   .sign()

  val verification = ApkVerifier.Builder(signedApk).build().verify()
  check(verification.isVerified) {
   "فشل التحقق من توقيع APK: " + verification.errors.joinToString("; ")
  }
 }

 private fun generateSigningKey() {
  val now = System.currentTimeMillis()
  val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
  val spec = KeyGenParameterSpec.Builder(
   KEY_ALIAS,
   KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
  )
   .setKeySize(2048)
   .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
   .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
   .setCertificateSubject(X500Principal("CN=ARABIFY Local APK Signer"))
   .setCertificateSerialNumber(BigInteger.ONE)
   .setCertificateNotBefore(Date(now - 86_400_000L))
   .setCertificateNotAfter(Date(now + 3_650L * 86_400_000L))
   .build()
  generator.initialize(spec)
  generator.generateKeyPair()
 }
}
