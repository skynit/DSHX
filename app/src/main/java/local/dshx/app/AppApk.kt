package local.dshx.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

/** 在手机内组装并签名独立 APK；只导出经过校验的定义，不复制使用数据或权限配置。 */
internal class AppApk(private val context: Context, private val store: AppStore) {
    data class Result(val file: File, val name: String)

    fun build(id: String): Result = synchronized(lock) {
        require(store.verify()) { store.error ?: "应用包需要重新检查。" }
        val row = store.status(id).getJSONArray("apps").optJSONObject(0) ?: error("应用已移除。")
        require(!row.has("error")) { "应用包校验失败，不能打包。" }
        val app = AppPackage.parse(row.getJSONObject("package"))
        val cache = File(context.cacheDir, "micro-apk").apply { check(isDirectory || mkdirs()) }
        cache.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.deleteRecursively() }
        val work = File(cache, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            val template = File(work, "runtime.apk")
            context.assets.open("micro-apk/runtime.apk").use { input -> template.outputStream().use(input::copyTo) }
            val prefs = context.getSharedPreferences("micro-apk-versions", Context.MODE_PRIVATE)
            val previous = prefs.getString("${app.id}.version", null)
            val oldCode = prefs.getInt("${app.id}.code", 0)
            val code = if (previous == app.version) oldCode else Math.addExact(oldCode, 1)
            require(code > 0)
            // 先登记编号；失败允许留下间隔，不能让下一次导出重用不同版本的编号。
            check(prefs.edit().putString("${app.id}.version", app.version).putInt("${app.id}.code", code).commit()) { "无法保存 APK 版本编号。" }
            val target = File(work, "${app.id}-${app.version}.apk")
            assemble(template, app, code, signer(), target)
            template.delete()
            Result(target, "${app.name}-${app.version}.apk")
        } catch (error: Exception) { work.deleteRecursively(); throw error }
    }

    private fun signer(): ApkSigner.SignerConfig {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "dshx-micro-apk"
        if (!keys.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=DSHX Local Micro Apps"))
                .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(4_102_444_800_000L)).build())
            generator.generateKeyPair()
        }
        return ApkSigner.SignerConfig.Builder("micro", keys.getKey(alias, null) as PrivateKey,
            listOf(keys.getCertificate(alias) as X509Certificate)).build()
    }

    companion object {
        private val lock = Any()
        fun packageName(id: String): String = "local.dshx.micro.a" + MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray()).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }

        internal fun assemble(template: File, app: AppPackage.Package, version: Int, signer: ApkSigner.SignerConfig, target: File) {
            val unsigned = File(target.parentFile, "unsigned.apk")
            try {
                val module = ApkModule.loadApkFile(template)
                try {
                    val manifest = module.androidManifest
                    manifest.setPackageName(packageName(app.id))
                    manifest.setVersionName(app.version); manifest.setVersionCode(version); manifest.setApplicationLabel(app.name)
                    if (app.network.isEmpty()) manifest.getUsesPermission("android.permission.INTERNET")?.removeSelf()
                    if (app.background == null) {
                        manifest.getUsesPermission("android.permission.FOREGROUND_SERVICE")?.removeSelf()
                        manifest.getUsesPermission("android.permission.POST_NOTIFICATIONS")?.removeSelf()
                    }
                    manifest.listApplicationElementsByTag("service").forEach { service ->
                        val name = service.searchAttributeByResourceId(AndroidManifestBlock.ID_name)?.valueAsString
                        if (name == "local.dshx.app.PhoneService" && !app.usesScreen || name == "local.dshx.app.MicroService" && app.background == null) service.removeSelf()
                        else if (name == "local.dshx.app.PhoneService") service.getOrCreateAndroidAttribute("label", AndroidManifestBlock.ID_label).setValueAsString(app.name + " · 手机操作")
                    }
                    module.tableBlock.listPackages().forEach { it.name = packageName(app.id) }
                    module.add(ByteInputSource(app.json.toByteArray(), "assets/micro-app/app.json"))
                    module.refreshManifest(); module.refreshTable()
                    module.writeApk(unsigned)
                } finally { module.close() }
                ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(target)
                    .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true)
                    .setV3SigningEnabled(false).setV4SigningEnabled(false).setCreatedBy("DSHX micro app").build().sign()
                val result = ApkVerifier.Builder(target).setMinCheckedPlatformVersion(26).build().verify()
                check(result.isVerified && result.isVerifiedUsingV2Scheme) { "APK 签名校验失败，请重新打包。" }
            } catch (error: Exception) { target.delete(); throw error }
            finally { unsigned.delete() }
        }
    }
}
