package se.lublin.mumla.voicechanger;

import android.content.Context;
import android.content.SharedPreferences;

import se.lublin.humla.audio.voicechanger.VoiceChanger;

/**
 * Persists the default voice-changer mode in a dedicated SharedPreferences
 * file and applies it to the singleton {@link VoiceChanger}. Default is OFF
 * so the voice changer is strictly opt-in.
 */
public final class VoiceChangerPrefs {

    private static final String PREFS = "voice_changer";
    private static final String KEY_MODE = "mode";

    private VoiceChangerPrefs() {}

    public static VoiceChanger.Mode load(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = sp.getString(KEY_MODE, VoiceChanger.Mode.OFF.name());
        try {
            return VoiceChanger.Mode.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return VoiceChanger.Mode.OFF;
        }
    }

    public static void save(Context ctx, VoiceChanger.Mode mode) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name())
                .apply();
    }

    /** Load the persisted mode and push it onto the shared VoiceChanger. */
    public static void apply(Context ctx) {
        VoiceChanger.shared().setMode(load(ctx));
    }
}
