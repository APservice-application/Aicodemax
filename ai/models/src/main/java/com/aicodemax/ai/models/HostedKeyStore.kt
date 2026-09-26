package com.aicodemax.ai.models

import java.util.UUID

/** Non-secret reference; only the vault can decrypt the actual value for an HTTPS request. */
data class HostedKeyRef(val id: String, val providerId: String, val label: String)

data class HostedRoute(val providerId: String, val modelId: String)

/** Implementations must keep keys off logs, project files and Android backups. */
interface HostedKeyStore {
    fun keys(providerId: String): List<HostedKeyRef>
    fun secret(id: String): String?
    fun add(providerId: String, apiKey: String): HostedKeyRef
    fun remove(id: String)
    fun model(providerId: String): String?
    fun selectModel(providerId: String, modelId: String)
    fun primaryProvider(): String?
    fun selectPrimary(providerId: String)
    fun networkConsent(): Boolean
    fun setNetworkConsent(allowed: Boolean)
}

/** Test-double only. Android builds use KeystoreHostedKeyStore in app/noBackupFilesDir. */
class InMemoryHostedKeyStore : HostedKeyStore {
    private data class Item(val ref: HostedKeyRef, val secret: String)
    private val items = mutableListOf<Item>()
    private val models = mutableMapOf<String, String>()
    private var primary: String? = null
    private var consent = false

    @Synchronized override fun keys(providerId: String): List<HostedKeyRef> =
        items.filter { it.ref.providerId == providerId }.map { it.ref }

    @Synchronized override fun secret(id: String): String? = items.firstOrNull { it.ref.id == id }?.secret

    @Synchronized override fun add(providerId: String, apiKey: String): HostedKeyRef {
        require(HostedProviderDirectory.find(providerId) != null && apiKey.isNotBlank())
        val ref = HostedKeyRef(UUID.randomUUID().toString(), providerId, "••••${apiKey.takeLast(4)}")
        items.add(Item(ref, apiKey.trim()))
        if (primary == null) primary = providerId
        consent = false // new party/key: user must review external-data/billing consent again
        return ref
    }

    @Synchronized override fun remove(id: String) {
        items.removeAll { it.ref.id == id }
        if (primary != null && keys(primary!!).isEmpty()) primary = items.firstOrNull()?.ref?.providerId
        consent = false
    }

    @Synchronized override fun model(providerId: String): String? = models[providerId]

    @Synchronized override fun selectModel(providerId: String, modelId: String) {
        require(HostedProviderDirectory.find(providerId) != null && modelId.isNotBlank())
        models[providerId] = modelId
        consent = false
    }

    @Synchronized override fun primaryProvider(): String? = primary
    @Synchronized override fun selectPrimary(providerId: String) {
        require(keys(providerId).isNotEmpty())
        primary = providerId
        consent = false
    }

    @Synchronized override fun networkConsent(): Boolean = consent
    @Synchronized override fun setNetworkConsent(allowed: Boolean) { consent = allowed }
}
