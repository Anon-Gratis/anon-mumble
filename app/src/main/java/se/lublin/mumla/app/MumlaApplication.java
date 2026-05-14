package se.lublin.mumla.app;

import static androidx.appcompat.app.AppCompatDelegate.setApplicationLocales;
import static androidx.core.os.LocaleListCompat.forLanguageTags;
import static androidx.core.os.LocaleListCompat.getEmptyLocaleList;
import static se.lublin.mumla.Settings.PREF_LANGUAGE;
import static se.lublin.mumla.Settings.PREF_THEME;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

public class MumlaApplication extends Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        applyTheme(preferences);
        preferences.registerOnSharedPreferenceChangeListener(this);
        // Anon fork: boot the embedded Tor daemon on app start. The bind is
        // deferred 2 s inside TorBridge to avoid racing with activity startup;
        // by the time the user taps [ CONNECT ] on the pre-populated server,
        // SOCKS5 on 127.0.0.1:9050 is ready.
        se.lublin.mumla.tor.TorBridge.start(this);
        seedAnonGratisServer();
        // Anon fork: load saved voice-changer mode into the shared DSP singleton
        // so it's applied on the next call without waiting for the user to open
        // settings.
        se.lublin.mumla.voicechanger.VoiceChangerPrefs.apply(this);
        // Anon fork: PIN/biometric lock + FLAG_SECURE on every activity.
        // Same files as the other 4 Anon apps. The gate is opt-in via the
        // settings entry — without a PIN configured, this just applies
        // FLAG_SECURE (Recent Apps blur + screenshot block) and no-ops the lock.
        new se.lublin.mumla.applock.AppLockGate(this).install();
    }

    /**
     * Anon fork: on first launch:
     *   1. Auto-generate a TLS client certificate (idempotent — only if none
     *      exists yet) and set it as the default. Mumble identifies users by
     *      cert hash; without a per-install cert every client has the SAME
     *      empty identity and murmur ghost-kicks them when a second user
     *      connects with the same username.
     *   2. Insert the anon.gratis Mumble .onion as the one and only seeded
     *      server with a unique-per-install username "anon-XXXX". Mumble also
     *      enforces unique usernames per channel; sharing "anon" across
     *      installs trips the same ghost-eviction path.
     *
     * <p>Both steps are idempotent and only run when their respective state is
     * empty.
     *
     * <p>The TLS *server* fingerprint is intentionally NOT pre-trusted: on
     * first connect Mumla pops the cert-trust dialog and the user is expected
     * to compare the displayed SHA-256 against the value at
     * anonymous.gratis/comms.html (also shown on the landing page served at
     * the same .onion over HTTP).
     */
    private void seedAnonGratisServer() {
        try {
            se.lublin.mumla.db.MumlaSQLiteDatabase db =
                    new se.lublin.mumla.db.MumlaSQLiteDatabase(this);
            se.lublin.mumla.Settings settings = se.lublin.mumla.Settings.getInstance(this);

            // Step 1: per-install TLS client cert.
            if (db.getCertificates() == null || db.getCertificates().isEmpty()) {
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    se.lublin.humla.net.HumlaCertificateGenerator.generateCertificate(baos);
                    String fileName = "anon-mumble-"
                            + new java.text.SimpleDateFormat(
                                    "yyyyMMdd-HHmmss", java.util.Locale.US)
                                    .format(new java.util.Date())
                            + ".p12";
                    se.lublin.mumla.db.DatabaseCertificate dc =
                            db.addCertificate(fileName, baos.toByteArray());
                    if (dc != null) {
                        settings.setDefaultCertificateId(dc.getId());
                        android.util.Log.i("AnonMumble",
                                "auto-generated client cert id=" + dc.getId());
                    }
                } catch (Exception e) {
                    android.util.Log.w("AnonMumble", "cert auto-gen failed", e);
                }
            }

            // Step 2: seed the server, with a unique anon-XXXX username.
            if (db.getServers().isEmpty()) {
                String suffix = randomHexSuffix(4);
                se.lublin.humla.model.Server seed = new se.lublin.humla.model.Server(
                        -1L,
                        "anon.gratis voice",
                        "n2dkyvz6fpzouggmmm3355bybqjcncl3c5hs7iaa3hfqkvjyftcllaid.onion",
                        64738,
                        "anon-" + suffix,
                        "");
                db.addServer(seed);
            }
            db.close();
        } catch (Throwable t) {
            android.util.Log.w("AnonMumble", "seedAnonGratisServer failed", t);
        }
    }

    private static String randomHexSuffix(int chars) {
        // SecureRandom — no install-specific identifier, just unique among peers.
        java.security.SecureRandom r = new java.security.SecureRandom();
        byte[] b = new byte[(chars + 1) / 2];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.substring(0, chars);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, @Nullable String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case PREF_LANGUAGE:
                String language = preferences.getString(PREF_LANGUAGE, "system");
                setApplicationLocales(language.equals("system") ? getEmptyLocaleList() : forLanguageTags(language));
                break;
            case PREF_THEME:
                applyTheme(preferences);
                break;
        }
    }

    private static void applyTheme(SharedPreferences preferences) {
        // The "system" and "force*" values are new (see preference_notranslate.xml).
        // We let other (older) value result in system default theme, and write that
        // to the preference store.
        switch (preferences.getString(PREF_THEME, "system")) {
            case "forceLight":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "forceDark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "system":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                preferences.edit().putString(PREF_THEME, "system").apply();
                break;
        }
    }
}
