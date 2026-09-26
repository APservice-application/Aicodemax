package com.aicodemax.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicodemax.ai.models.HostedKeyRef
import com.aicodemax.ai.models.HostedKeyStore
import com.aicodemax.ai.models.HostedProviderDirectory
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * BYOK secrets: AES-256-GCM key in Android Keystore, encrypted values + routing
 * metadata in noBackupFilesDir. No raw token in DataStore, backup, log or Git.
 * Reinstall/clear-data intentionally loses these credentials.
 */
class KeystoreHostedKeyStore(context: Context) : HostedKeyStore {
    private val directory = context.applicationContext.noBackupFilesDir
    private val file = File(directory, "hosted-ai-vault.properties")
    private val values = Properties()

    init {
        if (file.exists()) file.inputStream().use { values.load(it) }
    }

    @Synchronized override fun keys(providerId: String): List<HostedKeyRef> = values.stringPropertyNames()
        .asSequence()
        .filter { it.startsWith("key.") && it.endsWith(".provider") && values.getProperty(it) == providerId }
        .map { field ->
            val id = field.removePrefix("key.").removeSuffix(".provider")
            HostedKeyRef(id, providerId, values.getProperty("key.$id.label", "••••"))
        }.sortedBy { it.id }.toList()

    @Synchronized override fun secret(id: String): String? {
        val data = values.getProperty("key.$id.data") ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(data)
            if (bytes.size < 29) return null // 12 byte IV + >=1 byte ciphertext + 16 byte tag
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null // key may have been invalidated; never reveal ciphertext or token in an error
        }
    }

    @Synchronized override fun add(providerId: String, apiKey: String): HostedKeyRef {
        require(HostedProviderDirectory.find(providerId) != null) { "ไม่รู้จักผู้ให้บริการ" }
        val trimmed = apiKey.trim()
        require(trimmed.length >= 8 && trimmed.none { it == '\r' || it == '\n' }) { "API key ไม่ถูกต้อง" }
        val id = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val ref = HostedKeyRef(id, providerId, "••••${trimmed.takeLast(4)}")
        values.setProperty("key.$id.provider", providerId)
        values.setProperty("key.$id.data", Base64.getEncoder().encodeToString(encrypted))
        values.setProperty("key.$id.label", ref.label)
        if (primaryProvider() == null) values.setProperty("primary", providerId)
        values.setProperty("consent", "false") // config changed: re-approve new recipients/cost
        save()
        return ref
    }

    @Synchronized override fun remove(id: String) {
        val provider = values.getProperty("key.$id.provider") ?: return
        for (field in listOf("provider", "data", "label")) values.remove("key.$id.$field")
        if (keys(provider).isEmpty()) {
            values.remove("model.$provider")
            if (primaryProvider() == provider) values.setProperty("primary",
                HostedProviderDirectory.all.firstOrNull { keys(it.id).isNotEmpty() }?.id.orEmpty())
        }
        values.setProperty("consent", "false")
        save()
    }

    @Synchronized override fun model(providerId: String): String? =
        values.getProperty("model.$providerId")?.takeIf { it.isNotBlank() }

    @Synchronized override fun selectModel(providerId: String, modelId: String) {
        require(HostedProviderDirectory.find(providerId) != null && modelId.isNotBlank() && modelId.length <= 300)
        values.setProperty("model.$providerId", modelId)
        values.setProperty("consent", "false")
        save()
    }

    @Synchronized override fun primaryProvider(): String? = values.getProperty("primary")?.takeIf { it.isNotBlank() }

    @Synchronized override fun selectPrimary(providerId: String) {
        require(keys(providerId).isNotEmpty()) { "เพิ่มคีย์ก่อนเลือกผู้ให้บริการหลัก" }
        values.setProperty("primary", providerId)
        values.setProperty("consent", "false")
        save()
    }

    @Synchronized override fun networkConsent(): Boolean = values.getProperty("consent") == "true"

    @Synchronized override fun setNetworkConsent(allowed: Boolean) {
        values.setProperty("consent", allowed.toString())
        save()
    }

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun save() {
        val tmp = File(directory, "${file.name}.tmp")
        FileOutputStream(tmp).use { stream ->
            values.store(stream, "Encrypted API credentials - do not export or back up")
            stream.fd.sync()
        }
        check(tmp.renameTo(file)) { "บันทึก API key ไม่สำเร็จ" }
    }

    private companion object {
        const val ALIAS = "com.aicodemax.ai.hosted.byok.v1"
    }
}
