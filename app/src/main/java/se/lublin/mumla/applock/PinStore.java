package se.lublin.mumla.applock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
// import se.lublin.mumla.Config;  // logtag inlined below — Mumla has no Config class
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * App-lock PIN store. PIN is hashed with PBKDF2-HMAC-SHA256 (100k iterations,
 * 32-byte salt, 32-byte digest) before persisting; the encoded hash + salt
 * live inside an {@link EncryptedSharedPreferences} file whose master key is
 * Android Keystore-bound.
 *
 * <p>Two layers of defence: even if an attacker reads the prefs file off the
 * device, they get an AES-GCM ciphertext, and even if they could break that
 * they'd still face a PBKDF2 work factor before they could brute-force the
 * PIN.
 *
 * <p>No PIN recovery. Forgetting the PIN means clearing app data.
 */
public final class PinStore {

    private static final String PREFS_FILE = "anon_xmpp_lock";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_BIO = "bio_enabled";
    private static final int SALT_BYTES = 32;
    private static final int ITERATIONS = 100_000;
    private static final int DIGEST_BITS = 256;

    private final SharedPreferences prefs;

    public PinStore(final Context context) {
        SharedPreferences sp;
        try {
            final MasterKey master =
                    new MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();
            sp =
                    EncryptedSharedPreferences.create(
                            context,
                            PREFS_FILE,
                            master,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (final GeneralSecurityException | java.io.IOException e) {
            // Keystore unavailable — fall back to plain SharedPreferences so
            // the user can still configure the lock. Hash is still PBKDF2'd.
            Log.e("AnonMumble", "PinStore: EncryptedSharedPreferences failed, falling back", e);
            sp = context.getSharedPreferences(PREFS_FILE + "_plain", Context.MODE_PRIVATE);
        }
        this.prefs = sp;
    }

    public boolean hasPin() {
        return prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT);
    }

    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIO, false);
    }

    public void setBiometricEnabled(final boolean enabled) {
        prefs.edit().putBoolean(KEY_BIO, enabled).apply();
    }

    public void setPin(final char[] pin) {
        final byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        final byte[] hash = pbkdf2(pin, salt);
        prefs.edit()
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .apply();
    }

    public boolean verifyPin(final char[] pin) {
        final String storedHash = prefs.getString(KEY_HASH, null);
        final String storedSalt = prefs.getString(KEY_SALT, null);
        if (storedHash == null || storedSalt == null) return false;
        final byte[] expected = Base64.decode(storedHash, Base64.NO_WRAP);
        final byte[] salt = Base64.decode(storedSalt, Base64.NO_WRAP);
        final byte[] actual = pbkdf2(pin, salt);
        return constantTimeEquals(expected, actual);
    }

    /** Clear PIN + biometric flag. Used when disabling the lock. */
    public void clearPin() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_BIO).apply();
    }

    private static byte[] pbkdf2(final char[] pin, final byte[] salt) {
        final PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, DIGEST_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (final GeneralSecurityException e) {
            // PBKDF2WithHmacSHA256 is mandated by Android since API 26; we
            // targetSdk well above that. If we're really here something is
            // catastrophically wrong, so fail closed.
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean constantTimeEquals(final byte[] a, final byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }
}
