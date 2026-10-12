package app.areada.data.private

import android.content.Context
import android.content.SharedPreferences

object PrivateModeStore {
    private const val PREFERENCES_NAME = "areada_private_mode"
    private const val KEY_PASSWORD_SALT = "password_salt"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val KEY_MODE_ENABLED = "private_mode_enabled"

    private var cachedPrefs: SharedPreferences? = null
    private var cachedPrefsContext: Context? = null

    private fun prefs(context: Context): SharedPreferences {
        if (cachedPrefsContext !== context) {
            cachedPrefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            cachedPrefsContext = context
        }
        return cachedPrefs!!
    }

    fun hasPassword(context: Context): Boolean =
        prefs(context).getString(KEY_PASSWORD_HASH, null).orEmpty().isNotBlank()

    fun setPassword(
        context: Context,
        password: String,
    ) {
        val salt = PrivateModePassword.newSalt()
        val hash = PrivateModePassword.hash(salt, password)
        prefs(context).edit()
            .putString(KEY_PASSWORD_SALT, salt)
            .putString(KEY_PASSWORD_HASH, hash)
            .apply()
    }

    fun verifyPassword(
        context: Context,
        password: String,
    ): Boolean {
        val store = prefs(context)
        val salt = store.getString(KEY_PASSWORD_SALT, null).orEmpty()
        val hash = store.getString(KEY_PASSWORD_HASH, null).orEmpty()
        return PrivateModePassword.matches(salt, hash, password)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MODE_ENABLED, false)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_MODE_ENABLED, enabled).apply()
    }
}