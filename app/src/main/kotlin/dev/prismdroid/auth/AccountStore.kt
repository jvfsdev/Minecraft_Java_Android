package dev.prismdroid.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.prismdroid.PrismDroidApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Account(
    val uuid: String,
    val name: String,
    val mcAccessToken: String,
    val mcExpiresAt: Long,
    val msaRefreshToken: String,
    val xuid: String = "0",
)

@Serializable
data class AccountsFile(val accounts: List<Account> = emptyList(), val active: String? = null)

/** Accounts persisted as AES-GCM ciphertext; the key never leaves the Android Keystore. */
object AccountStore {
    private const val ALIAS = "prismdroid-accounts"
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    @Synchronized fun load(): AccountsFile {
        val f = PrismDroidApp.paths.accounts
        if (!f.isFile) return AccountsFile()
        return runCatching {
            val bytes = f.readBytes()
            val iv = bytes.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            json.decodeFromString<AccountsFile>(String(c.doFinal(bytes, 12, bytes.size - 12)))
        }.getOrElse { AccountsFile() }
    }

    @Synchronized fun save(data: AccountsFile) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(json.encodeToString(data).toByteArray())
        val f = PrismDroidApp.paths.accounts
        val tmp = java.io.File(f.path + ".tmp")
        tmp.writeBytes(out); tmp.renameTo(f)
    }

    fun upsert(acc: Account, makeActive: Boolean = true) {
        val cur = load()
        save(AccountsFile(cur.accounts.filter { it.uuid != acc.uuid } + acc,
            if (makeActive) acc.uuid else cur.active ?: acc.uuid))
    }

    fun remove(uuid: String) {
        val cur = load()
        val rest = cur.accounts.filter { it.uuid != uuid }
        save(AccountsFile(rest, if (cur.active == uuid) rest.firstOrNull()?.uuid else cur.active))
    }

    fun active(): Account? = load().let { f -> f.accounts.firstOrNull { it.uuid == f.active } ?: f.accounts.firstOrNull() }
}
