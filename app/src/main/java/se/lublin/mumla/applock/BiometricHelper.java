package se.lublin.mumla.applock;

import android.content.Context;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Thin wrapper around {@link BiometricPrompt}. We allow {@link
 * BiometricManager.Authenticators#BIOMETRIC_STRONG} only (Class 3) — Class 2
 * weak biometric is rejected because we want a hardware-backed Keystore-bind
 * level of trust for unlock shortcuts.
 */
public final class BiometricHelper {

    public interface OnSuccess {
        void run();
    }

    public interface OnFail {
        void run(int errCode, CharSequence errMessage);
    }

    private BiometricHelper() {}

    /** True iff the device has enrolled strong biometric and prompt() will succeed. */
    public static boolean isAvailable(final Context context) {
        return BiometricManager.from(context)
                        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Returns a stable string explaining why biometric isn't usable, or null
     * if it is. Used by the settings UI to render a hint to the user.
     */
    public static String unavailableReason(final Context context) {
        final int status =
                BiometricManager.from(context)
                        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        switch (status) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return null;
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "No biometric hardware";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "Biometric hardware unavailable";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "No biometric enrolled in system settings";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return "Biometric requires security update";
            default:
                return "Biometric unavailable";
        }
    }

    public static void prompt(
            final FragmentActivity activity,
            final String title,
            final String subtitle,
            final OnSuccess onSuccess,
            final OnFail onFail) {
        final Executor executor = Executors.newSingleThreadExecutor();
        final BiometricPrompt prompt =
                new BiometricPrompt(
                        activity,
                        executor,
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    BiometricPrompt.AuthenticationResult result) {
                                activity.runOnUiThread(onSuccess::run);
                            }

                            @Override
                            public void onAuthenticationError(
                                    int errorCode, CharSequence errString) {
                                activity.runOnUiThread(() -> onFail.run(errorCode, errString));
                            }
                        });
        final BiometricPrompt.PromptInfo info =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setNegativeButtonText("Use PIN")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setConfirmationRequired(false)
                        .build();
        prompt.authenticate(info);
    }
}
