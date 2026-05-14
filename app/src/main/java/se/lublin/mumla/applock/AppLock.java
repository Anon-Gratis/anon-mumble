package se.lublin.mumla.applock;

/**
 * Process-wide app lock state. Set lockedness through static methods so any
 * Activity (or the {@link AppLockGate} lifecycle observer) can check and
 * mutate it from a single source of truth.
 *
 * <p>Re-locks after the app has been in the background for longer than {@link
 * #GRACE_MS}.
 */
public final class AppLock {

    /** Grace window after backgrounding during which we DON'T re-lock. */
    public static final long GRACE_MS = 60_000L;

    private static volatile boolean locked = false;
    private static volatile long backgroundedAt = 0L;

    private AppLock() {}

    public static boolean isLocked() {
        return locked;
    }

    /** Called from LockActivity after correct PIN / biometric. */
    public static void markUnlocked() {
        locked = false;
        backgroundedAt = 0L;
    }

    /** Force lock — used by settings when the user disables and re-enables. */
    public static void markLocked() {
        locked = true;
    }

    /** Called when the app moves to background (last activity stopped). */
    public static void onAppBackgrounded() {
        backgroundedAt = System.currentTimeMillis();
    }

    /**
     * Called when any activity comes to foreground. Returns true iff the lock
     * screen should be shown.
     */
    public static boolean onAppForegrounded(final boolean pinConfigured) {
        if (!pinConfigured) {
            locked = false;
            return false;
        }
        if (locked) return true;
        final long bg = backgroundedAt;
        if (bg > 0L && System.currentTimeMillis() - bg > GRACE_MS) {
            locked = true;
        }
        backgroundedAt = 0L;
        return locked;
    }

    /**
     * Called from {@link eu.siacs.conversations.Conversations#onCreate()} when
     * a PIN is configured. The first activity to start MUST go through
     * LockActivity.
     */
    public static void lockOnProcessStart() {
        locked = true;
    }
}
