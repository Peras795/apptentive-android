package com.apptentive.android.sdk.encryption;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.annotation.NonNull;

import com.apptentive.android.sdk.ApptentiveLog;
import com.apptentive.android.sdk.debug.ErrorMetrics;
import com.apptentive.android.sdk.encryption.resolvers.KeyResolver;
import com.apptentive.android.sdk.encryption.resolvers.KeyResolverFactory;
import com.apptentive.android.sdk.util.StringUtils;

import java.util.UUID;

import static com.apptentive.android.sdk.ApptentiveLog.hideIfSanitized;
import static com.apptentive.android.sdk.ApptentiveLogTag.SECURITY;

/**
 * Class responsible for managing the master encryption key (generation, storage and retrieval).
 */
public final class SecurityManager {
	private static final String PREFS_KEY_ALIAS = "alias";
	private static final String PREFS_SDK_VERSION_CODE = "version_code";

	/**
	 * We would force Key Store API version to this
	 */
	private static final int LEGACY_KEY_STORE_API = 18;

	/**
	 * The highest API version which would resolve in no encryption operations
	 */
	private static final int LEGACY_KEY_STORE_API_NO_OP = 17;

	private static EncryptionKey masterKey;

	//region Initialization

	public static void init(Context context, boolean shouldEncryptStorage) {
		if (context == null) {
			throw new IllegalArgumentException("Context is null");
		}

		// get the name of the alias
		KeyInfo keyInfo = resolveKeyInfo(context, shouldEncryptStorage);
		ApptentiveLog.v(SECURITY, "Secret key info: %s", keyInfo);

		// load or generate the key
		masterKey = resolveMasterKey(context, keyInfo);
	}

	public static void clear(Context context) {
		SharedPreferences prefs = getPrefs(context);
		prefs.edit().clear().apply();
	}

	private static KeyInfo resolveKeyInfo(Context context, boolean shouldEncryptStorage) {
		// in order to avoid potential naming collisions we would generate a unique name for the alias and
		// store it in the SharedPreferences
		SharedPreferences prefs = getPrefs(context);

		String keyAlias = prefs.getString(PREFS_KEY_ALIAS, null);
		int versionCode = prefs.getInt(PREFS_SDK_VERSION_CODE, 0);
		if (StringUtils.isNullOrEmpty(keyAlias) || versionCode == 0) {
			keyAlias = generateUniqueKeyAlias();
			// We need to force the legacy KeyStore API on new installs since the modern 23+ API randomly
			// fails on both devices and emulators. All the existing clients would continue using the version
			// originally assigned on the first launch.
			// more info: https://stackoverflow.com/questions/36488219/android-security-keystoreexception-invalid-key-blob
			if (shouldEncryptStorage) {
				versionCode = Math.min(LEGACY_KEY_STORE_API, Build.VERSION.SDK_INT); // we still want to keep API version less than 18 (no-op)
			} else {
				versionCode = LEGACY_KEY_STORE_API_NO_OP; // if user opts out of encryption - use no-op API
			}
			prefs.edit()
				.putString(PREFS_KEY_ALIAS, keyAlias)
				.putInt(PREFS_SDK_VERSION_CODE, versionCode)
				.apply();
			ApptentiveLog.v(SECURITY, "Generated new key info");
		}

		return new KeyInfo(keyAlias, versionCode);
	}

	private static @NonNull EncryptionKey resolveMasterKey(Context context, KeyInfo keyInfo) {
		try {
			KeyResolver keyResolver = KeyResolverFactory.createKeyResolver(keyInfo.versionCode);
			return keyResolver.resolveKey(context, keyInfo.alias);
		} catch (Exception e) {
			ApptentiveLog.e(SECURITY, e, "Exception while resolving secret key for alias '%s'. Encryption might not work correctly!", hideIfSanitized(keyInfo.alias));
			ErrorMetrics.logException(e); // TODO: add more context info
			return EncryptionKey.CORRUPTED;
		}
	}

	//endregion

	//region Getters/Setters

	public static @NonNull EncryptionKey getMasterKey() {
		return masterKey;
	}
	//endregion

	//region Helpers

	private static String generateUniqueKeyAlias() {
		return "apptentive-key-" + UUID.randomUUID().toString();
	}

	private static SharedPreferences getPrefs(Context context) {
		return context.getSharedPreferences("com.apptentive.sdk.security", Context.MODE_PRIVATE);
	}

	//endregion

	//region Helper classes

	static class KeyInfo {
		/**
		 * Alias name for KeyStore.
		 */
		final String alias;

		/**
		 * Android SDK version code at the time the target key was generated.
		 */
		final int versionCode;

		KeyInfo(String alias, int versionCode) {
			if (StringUtils.isNullOrEmpty(alias)) {
				throw new IllegalArgumentException("Key alias name is null or empty");
			}
			if (versionCode < 1) {
				throw new IllegalArgumentException("Invalid SDK version code");
			}

			this.alias = alias;
			this.versionCode = versionCode;
		}

		@Override
		public String toString() {
			return StringUtils.format("KeyInfo: alias=%s versionCode=%d", hideIfSanitized(alias), versionCode);
		}
	}

	//endregion
}
