package com.agentos.shell.tools

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encrypted vault for sensitive info (bank details). The plaintext is encrypted with AES-256-GCM
 * under a key derived from the user's VAULT PIN (PBKDF2) — so the PIN is required to read it and the key is
 * never stored anywhere. The encrypted blob can safely live in the brain / sync to the server as ciphertext
 * (matches the vault design in ACCOUNT_AND_SYNC.md). Forgetting the PIN means the data is unrecoverable —
 * that is the privacy guarantee.
 *
 * (Biometric convenience unlock can be layered on later via the Android Keystore; the PIN is the portable,
 * cross-device root.)
 */
object BankVault {
    private const val PREF = "slyos_vault"
    private const val K_SALT = "salt"
    private const val K_BLOB = "blob"     // base64(iv | ciphertext)
    private const val K_TS = "ts"         // updated_at for last-write-wins sync
    private const val ITERS = 210_000
    private const val KEYLEN = 256

    data class Item(val label: String, val value: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context): Boolean = prefs(ctx).getString(K_SALT, null) != null

    private fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERS, KEYLEN)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    private fun encrypt(key: SecretKeySpec, plain: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKeySpec, blob: String): ByteArray {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12); val ct = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    private fun serialize(items: List<Item>): ByteArray {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("label", it.label).put("value", it.value)) }
        return arr.toString().toByteArray(Charsets.UTF_8)
    }

    private fun parse(bytes: ByteArray): List<Item> = try {
        val arr = JSONArray(String(bytes, Charsets.UTF_8))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Item(it.optString("label"), it.optString("value")) } }
    } catch (e: Exception) { emptyList() }

    /** Create the vault with a PIN (encrypts an empty set). */
    fun setup(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)
        prefs(ctx).edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_BLOB, encrypt(key, serialize(emptyList())))
            .putLong(K_TS, System.currentTimeMillis())
            .apply()
        try { MemoryLog.add(ctx, "note", "Bank vault", "Bank info vault created (locked, encrypted).", "Vault") } catch (e: Exception) {}
    }

    /** Unlock and return the items, or null if the PIN is wrong / vault missing. */
    fun unlock(ctx: Context, pin: String): List<Item>? {
        val saltB64 = prefs(ctx).getString(K_SALT, null) ?: return null
        val blob = prefs(ctx).getString(K_BLOB, null) ?: return null
        return try { parse(decrypt(deriveKey(pin, Base64.decode(saltB64, Base64.NO_WRAP)), blob)) } catch (e: Exception) { null }
    }

    /** Replace the whole item set (must pass the correct PIN). Returns true on success. */
    fun save(ctx: Context, pin: String, items: List<Item>): Boolean {
        val saltB64 = prefs(ctx).getString(K_SALT, null) ?: return false
        // Verify the PIN first by trying to unlock.
        if (unlock(ctx, pin) == null) return false
        val key = deriveKey(pin, Base64.decode(saltB64, Base64.NO_WRAP))
        prefs(ctx).edit().putString(K_BLOB, encrypt(key, serialize(items))).putLong(K_TS, System.currentTimeMillis()).apply()
        try { MemoryLog.add(ctx, "note", "Bank vault", "Bank info updated (${items.size} item(s), locked).", "Vault") } catch (e: Exception) {}
        return true
    }

    // ---- Sync helpers: only the non-secret salt + the CIPHERTEXT ever leave the device ----------------
    fun cipherBlob(ctx: Context): String = prefs(ctx).getString(K_BLOB, "").orEmpty()
    fun saltB64(ctx: Context): String = prefs(ctx).getString(K_SALT, "").orEmpty()
    fun updatedAt(ctx: Context): Long = prefs(ctx).getLong(K_TS, 0L)

    /** Apply a vault pulled from the server (ciphertext + salt). The PIN is still required to read it. */
    fun importFromSync(ctx: Context, saltB64: String, blob: String, ts: Long) {
        if (saltB64.isBlank() || blob.isBlank()) return
        if (ts <= updatedAt(ctx)) return   // local is newer or equal
        prefs(ctx).edit().putString(K_SALT, saltB64).putString(K_BLOB, blob).putLong(K_TS, ts).apply()
    }
}
