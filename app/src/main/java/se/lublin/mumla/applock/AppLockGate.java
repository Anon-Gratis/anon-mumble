package se.lublin.mumla.applock;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * Glue between {@link AppLock} and the Android lifecycle. Registered from
 * {@code MumlaApplication.onCreate()}.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li>{@code FLAG_SECURE} on every activity at creation — blanks the
 *       Recent Apps thumbnail and blocks in-app screenshots, independent of
 *       whether a PIN is configured.
 *   <li>Lock gate: when any activity comes to foreground while the app is
 *       locked, redirect to {@link LockActivity}. Exempts {@link LockActivity}
 *       itself (avoids recursion) and the boot-splash transition activity.
 * </ul>
 *
 * <p>Process-wide background/foreground events are observed via {@link
 * ProcessLifecycleOwner} so the lock state machine sees a single transition
 * even when multiple activities are involved.
 */
public final class AppLockGate
        implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private static final String BOOT_SPLASH = "BootSplashActivity";

    private final Application app;

    public AppLockGate(final Application app) {
        this.app = app;
    }

    /** Install both the activity lifecycle hook and the process-wide hook. */
    public void install() {
        // If a PIN is set, the process starts locked; the first activity to
        // resume will route through LockActivity.
        if (new PinStore(app).hasPin()) {
            AppLock.lockOnProcessStart();
        }
        app.registerActivityLifecycleCallbacks(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    // ── ProcessLifecycleOwner observer ──────────────────────────────────────

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        AppLock.onAppBackgrounded();
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        AppLock.onAppForegrounded(new PinStore(app).hasPin());
    }

    // ── ActivityLifecycleCallbacks ──────────────────────────────────────────

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        // FLAG_SECURE applied early so it covers the activity's first frame.
        activity.getWindow()
                .setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // Skip the lock activity itself (would recurse) and the brief boot
        // splash transition (no user-visible content of value).
        if (activity instanceof LockActivity) return;
        if (BOOT_SPLASH.equals(activity.getClass().getSimpleName())) return;
        if (!AppLock.isLocked()) return;
        if (!new PinStore(app).hasPin()) {
            AppLock.markUnlocked();
            return;
        }
        // Overlay the lock screen on top. Don't finish() the underlying
        // activity — when LockActivity finishes after unlock, it resumes
        // cleanly underneath.
        final Intent intent = new Intent(activity, LockActivity.class);
        activity.startActivity(intent);
    }

    // ── Unused lifecycle callbacks ──────────────────────────────────────────

    @Override
    public void onActivityStarted(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {}
}
