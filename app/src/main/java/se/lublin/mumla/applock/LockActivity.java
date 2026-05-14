package se.lublin.mumla.applock;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import se.lublin.mumla.R;

/**
 * Full-screen unlock gate. Shown by {@link AppLockGate} whenever any activity
 * resumes while {@link AppLock#isLocked()} is true and a PIN is configured.
 *
 * <p>Back press minimizes the app instead of bypassing the lock.
 */
public class LockActivity extends AppCompatActivity {

    private EditText pinField;
    private Button unlockBtn;
    private Button biometricBtn;
    private TextView errorText;
    private PinStore pinStore;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Belt-and-braces — AppLockGate sets FLAG_SECURE on every activity,
        // but the lock screen is the highest-stakes one. Re-set here.
        getWindow()
                .setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_app_lock);

        pinStore = new PinStore(getApplicationContext());

        pinField = findViewById(R.id.applock_pin_field);
        unlockBtn = findViewById(R.id.applock_unlock_btn);
        biometricBtn = findViewById(R.id.applock_biometric_btn);
        errorText = findViewById(R.id.applock_error_text);

        unlockBtn.setOnClickListener(v -> attemptPinUnlock());
        pinField.setOnEditorActionListener(
                (textView, actionId, event) -> {
                    attemptPinUnlock();
                    return true;
                });

        if (pinStore.isBiometricEnabled() && BiometricHelper.isAvailable(getApplicationContext())) {
            biometricBtn.setVisibility(View.VISIBLE);
            biometricBtn.setOnClickListener(v -> promptBiometric());
            // Auto-prompt on launch so the user doesn't have to tap.
            biometricBtn.post(this::promptBiometric);
        }
    }

    @Override
    public void onBackPressed() {
        // Don't let the user dismiss the lock by pressing back; push the app
        // to background instead.
        moveTaskToBack(true);
    }

    private void attemptPinUnlock() {
        final String entered = pinField.getText().toString();
        if (TextUtils.isEmpty(entered)) return;
        final char[] pin = entered.toCharArray();
        final boolean ok = pinStore.verifyPin(pin);
        java.util.Arrays.fill(pin, '0');
        pinField.getText().clear();
        if (ok) {
            unlock();
        } else {
            errorText.setText("incorrect PIN");
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private void promptBiometric() {
        BiometricHelper.prompt(
                this,
                "Unlock Anon Mumble",
                "Use your biometric to unlock",
                this::unlock,
                (errCode, errMessage) -> {
                    /* PIN path remains available — no need to shout */
                });
    }

    private void unlock() {
        AppLock.markUnlocked();
        // Don't relaunch any activity. The activity behind us already has
        // setContentView complete (we never blocked its onCreate), so a plain
        // finish() will resume it cleanly via its onResume hook.
        finish();
    }
}
