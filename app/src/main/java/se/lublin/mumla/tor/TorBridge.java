package se.lublin.mumla.tor;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import org.torproject.jni.TorService;

/**
 * Owns the embedded Tor lifecycle and surfaces its bootstrap state. The
 * underlying library is Guardian Project's tor-android — the same one used by
 * Anon XMPP and Anon Mail. The Anon fork of Mumla embeds Tor instead of
 * delegating to Orbot, so this class replaces the package's previous
 * {@code OrbotHelper} contract.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>{@code bindService} rather than {@code startForegroundService}: the
 *       5 s startForeground deadline on Android 14+ is uncatchable and crashes
 *       the app if Tor bootstraps slowly. Binding keeps the service alive
 *       without requiring a notification posted within the deadline.
 *   <li>Bind is deferred 2 s after the app's onCreate to avoid racing with
 *       activity startup.
 *   <li>Status is volatile-cached so any fresh listener immediately sees the
 *       current state instead of waiting for the next broadcast.
 * </ul>
 */
public final class TorBridge {

    private static final String TAG = "AnonMumble.TorBridge";

    public static final String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";
    public static final String EXTRA_STATUS  = "org.torproject.android.intent.extra.STATUS";

    public static final String STATUS_ON       = "ON";
    public static final String STATUS_OFF      = "OFF";
    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_STOPPING = "STOPPING";

    /**
     * SOCKS5 endpoint the embedded Tor binds to.
     *
     * <p>Per-app port to avoid collisions when multiple Anon-Tor apps run on
     * the same device — they all embed info.guardianproject:tor-android which
     * defaults to 9050, so whichever app launches first wins the port and the
     * rest fail silently with EADDRINUSE (tor binary exits, never broadcasts
     * STATUS_ON, the host app blocks forever waiting on a circuit).
     *
     * <p>Assignments (keep in sync with the other Anon-Tor apps):
     * <pre>
     *   Anon XMPP        → 9050   (legacy default, unchanged)
     *   Anon Mail        → 9150
     *   Anon Mumble      → 9250   (this app)
     *   Anon WhistleBlower → 9450
     *   Anon Social      → 9550
     * </pre>
     */
    public static final String SOCKS_HOST = "127.0.0.1";
    public static final int SOCKS_PORT = 9250;

    private static volatile String sStatus = "UNKNOWN";

    private static ServiceConnection sConnection;
    private static BroadcastReceiver sReceiver;
    private static final List<StatusListener> sListeners = new ArrayList<>();

    private TorBridge() {}

    public static String status() { return sStatus; }
    public static boolean isReady() { return STATUS_ON.equals(sStatus); }

    public interface StatusListener { void onStatus(String status); }

    public static synchronized void start(final Context appContext) {
        if (sConnection != null) return;
        // Override the AAR's default 9050 BEFORE the service starts. TorService
        // reads its socksPort static field when generating torrc, so this must
        // happen pre-bindService. See SOCKS_PORT javadoc for the cross-app plan.
        TorService.socksPort = SOCKS_PORT;
        installStatusReceiver(appContext.getApplicationContext());

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.d(TAG, "embedded Tor bound: " + name + " (SOCKS " + SOCKS_PORT + ")");
                    }
                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.w(TAG, "embedded Tor disconnected: " + name);
                    }
                };
                sConnection = conn;
                Intent intent = new Intent(appContext, TorService.class);
                boolean ok = appContext.getApplicationContext()
                        .bindService(intent, conn, Context.BIND_AUTO_CREATE);
                Log.d(TAG, "embedded Tor bindService -> " + ok + " (SOCKS " + SOCKS_PORT + ")");
            } catch (Throwable t) {
                Log.e(TAG, "could not bind embedded Tor", t);
            }
        }, 2000L);
    }

    private static void installStatusReceiver(Context ctx) {
        if (sReceiver != null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                String s = intent.getStringExtra(EXTRA_STATUS);
                if (s == null) return;
                sStatus = s;
                synchronized (sListeners) {
                    for (StatusListener l : sListeners) {
                        try { l.onStatus(s); } catch (Throwable ignored) {}
                    }
                }
            }
        };
        sReceiver = r;
        ContextCompat.registerReceiver(
                ctx, r, new IntentFilter(ACTION_STATUS),
                ContextCompat.RECEIVER_EXPORTED);
    }

    /** Subscribe to status changes. Returns a Runnable that unsubscribes. */
    public static Runnable observe(StatusListener listener) {
        synchronized (sListeners) { sListeners.add(listener); }
        try { listener.onStatus(sStatus); } catch (Throwable ignored) {}
        return () -> { synchronized (sListeners) { sListeners.remove(listener); } };
    }
}
