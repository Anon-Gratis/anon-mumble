/*
 * Anon-fork addition: forensic-grade voice anonymization for Mumble.
 * Lives in humla so it sits between AudioRecord.read() and the Opus encoder.
 *
 * The DSP design is identical to Anon XMPP's voice changer:
 *   - STFT with 256-sample frames, 50% overlap (HOP = 128), Hann window.
 *   - ROBOT: random phase per FFT bin per frame. Phase carries the speaker-
 *     identifying micro-structure; uniform random over [0, 2π) destroys that
 *     information. Combined with 47 Hz ring modulation to inject harmonics
 *     the original voice didn't have.
 *   - MASK: spectral envelope flattened across 8 log-spaced bands + same
 *     random-phase + ring-mod treatment. Sounds more "human" than ROBOT.
 *   - No plain pitch-shift mode (pitch shift is reversible — apply inverse
 *     pitch shift to recover original. Not forensically useful).
 *
 * Operates on 16-bit signed PCM short[] (humla's native buffer format).
 */
package se.lublin.humla.audio.voicechanger;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class VoiceChanger {

    public enum Mode { OFF, ROBOT, MASK, ANONYMOUS }

    private static final int FFT_SIZE = 256;
    private static final int HOP = 128;
    private static final SecureRandom RAND = new SecureRandom();

    private volatile Mode mode = Mode.OFF;
    private int sampleRate = 48000;

    private final float[] window = new float[FFT_SIZE];
    private final float[] inputAccum = new float[FFT_SIZE];
    private int inputFill = 0;
    private final float[] outputOverlap = new float[FFT_SIZE];
    private final Deque<Float> outputQueue = new ArrayDeque<>();
    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private double ringPhase = 0.0;
    // ANONYMOUS-mode modulators — slow, must be continuous across frames.
    private double tremoloPhase = 0.0;
    private double carrierBuzzPhase = 0.0;

    private static volatile VoiceChanger SHARED;

    public static VoiceChanger shared() {
        VoiceChanger v = SHARED;
        if (v == null) {
            synchronized (VoiceChanger.class) {
                if (SHARED == null) SHARED = new VoiceChanger();
                v = SHARED;
            }
        }
        return v;
    }

    private VoiceChanger() {
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }
    }

    public void setMode(Mode m) { this.mode = m; }
    public Mode getMode() { return mode; }
    public void setSampleRate(int hz) { this.sampleRate = hz; }

    public synchronized void reset() {
        Arrays.fill(inputAccum, 0f);
        Arrays.fill(outputOverlap, 0f);
        inputFill = 0;
        outputQueue.clear();
        ringPhase = 0.0;
        tremoloPhase = 0.0;
        carrierBuzzPhase = 0.0;
    }

    /**
     * Mutate {@code count} 16-bit PCM samples in {@code buf} in place. When mode
     * is OFF, returns immediately without touching the buffer.
     *
     * Note: the STFT pipeline introduces a one-frame delay (~128 samples =
     * 2.7 ms at 48 kHz, 8 ms at 16 kHz). The output queue drains one sample
     * per input sample after the first frame fills, so callers see a
     * continuous stream.
     */
    public synchronized void processInPlace(short[] buf, int count) {
        Mode m = mode;
        if (m == Mode.OFF) return;
        if (count <= 0) return;

        for (int i = 0; i < count; i++) {
            float sample = buf[i] / 32768.0f;
            inputAccum[inputFill++] = sample;
            if (inputFill == FFT_SIZE) {
                processFrame(m);
                System.arraycopy(inputAccum, HOP, inputAccum, 0, FFT_SIZE - HOP);
                inputFill = FFT_SIZE - HOP;
            }

            float out;
            if (outputQueue.isEmpty()) {
                out = 0f;
            } else {
                out = outputQueue.pollFirst();
            }
            int clipped = Math.round(out * 32767.0f);
            if (clipped > 32767) clipped = 32767;
            else if (clipped < -32768) clipped = -32768;
            buf[i] = (short) clipped;
        }
    }

    private void processFrame(Mode m) {
        for (int i = 0; i < FFT_SIZE; i++) {
            re[i] = inputAccum[i] * window[i];
            im[i] = 0f;
        }

        fft(re, im, false);

        int halfN = FFT_SIZE / 2;
        if (m == Mode.ROBOT) {
            for (int k = 1; k < halfN; k++) {
                float mag = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                double phase = RAND.nextDouble() * 2.0 * Math.PI;
                re[k] = (float) (mag * Math.cos(phase));
                im[k] = (float) (mag * Math.sin(phase));
                re[FFT_SIZE - k] = re[k];
                im[FFT_SIZE - k] = -im[k];
            }
        } else if (m == Mode.MASK) {
            float[] mags = new float[halfN + 1];
            for (int k = 0; k <= halfN; k++) {
                mags[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            }
            int bands = 8;
            int binsPerBand = halfN / bands;
            for (int b = 0; b < bands; b++) {
                int from = b * binsPerBand;
                int to = (b == bands - 1) ? halfN : from + binsPerBand;
                float sum = 0f;
                for (int k = from; k < to; k++) sum += mags[k];
                float avg = sum / (to - from);
                for (int k = from; k < to; k++) mags[k] = avg;
            }
            for (int k = 1; k < halfN; k++) {
                double phase = RAND.nextDouble() * 2.0 * Math.PI;
                re[k] = (float) (mags[k] * Math.cos(phase));
                im[k] = (float) (mags[k] * Math.sin(phase));
                re[FFT_SIZE - k] = re[k];
                im[FFT_SIZE - k] = -im[k];
            }
        } else if (m == Mode.ANONYMOUS) {
            // Channel-vocoder character — the V-for-Vendetta / whistleblower
            // voice. Input formant envelope is preserved (speech remains
            // intelligible) but the speaker's pitch + harmonics are
            // entirely replaced by a fixed sawtooth carrier at F0 ≈ 85 Hz.
            // Random phase per bin and smoothed magnitudes destroy speaker
            // identity; the fixed carrier destroys pitch information
            // irrecoverably (no inverse function exists).
            float[] mags = new float[halfN + 1];
            for (int k = 0; k <= halfN; k++) {
                mags[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            }
            // Smooth magnitude with triangular kernel of width ±6 bins.
            float[] smooth = new float[halfN + 1];
            int kernel = 6;
            for (int k = 0; k <= halfN; k++) {
                float sum = 0f, weight = 0f;
                for (int j = -kernel; j <= kernel; j++) {
                    int kk = k + j;
                    if (kk >= 0 && kk <= halfN) {
                        float w = (kernel + 1 - Math.abs(j));
                        sum += mags[kk] * w;
                        weight += w;
                    }
                }
                smooth[k] = sum / weight;
            }
            // Sawtooth carrier weights at multiples of F0, each smeared
            // across ±2 bins so the comb isn't a knife-edge of nulls.
            double f0 = 85.0;
            double carrierBinD = f0 * FFT_SIZE / sampleRate;
            float[] carrier = new float[halfN + 1];
            int n = 1;
            while (true) {
                double centerD = n * carrierBinD;
                if (centerD > halfN) break;
                int center = (int) centerD;
                float ampN = 1f / n;
                int width = 2;
                for (int off = -width; off <= width; off++) {
                    int k = center + off;
                    if (k >= 1 && k <= halfN) {
                        float falloff = 1f - (float) Math.abs(off) / (width + 1f);
                        float w = ampN * falloff;
                        if (w > carrier[k]) carrier[k] = w;
                    }
                }
                n++;
            }
            for (int k = 1; k < halfN; k++) {
                float magOut = smooth[k] * carrier[k];
                double phase = RAND.nextDouble() * 2.0 * Math.PI;
                re[k] = (float) (magOut * Math.cos(phase));
                im[k] = (float) (magOut * Math.sin(phase));
                re[FFT_SIZE - k] = re[k];
                im[FFT_SIZE - k] = -im[k];
            }
        }
        im[0] = 0f;
        im[halfN] = 0f;

        fft(re, im, true);

        for (int i = 0; i < FFT_SIZE; i++) {
            outputOverlap[i] += re[i] * window[i];
        }
        // Mode-specific modulators applied during emit.
        double ringStep = 2.0 * Math.PI * 47.0 / sampleRate;
        double tremStep = 2.0 * Math.PI * 6.0 / sampleRate;
        double buzzStep = 2.0 * Math.PI * 30.0 / sampleRate;
        double tremDepth = 0.35;
        double buzzDepth = 0.18;

        for (int i = 0; i < HOP; i++) {
            float s0 = outputOverlap[i];
            float sample;
            if (m == Mode.ANONYMOUS) {
                double trem = 1.0 - tremDepth + tremDepth * Math.cos(tremoloPhase);
                double buzz = 1.0 - buzzDepth + buzzDepth * Math.cos(carrierBuzzPhase);
                tremoloPhase += tremStep;
                if (tremoloPhase > 2.0 * Math.PI) tremoloPhase -= 2.0 * Math.PI;
                carrierBuzzPhase += buzzStep;
                if (carrierBuzzPhase > 2.0 * Math.PI) carrierBuzzPhase -= 2.0 * Math.PI;
                // Vocoder output is energy-light; nudge gain up.
                sample = (float) (s0 * trem * buzz * 2.6);
            } else {
                sample = (float) (s0 * Math.cos(ringPhase));
                ringPhase += ringStep;
                if (ringPhase > 2.0 * Math.PI) ringPhase -= 2.0 * Math.PI;
            }
            outputQueue.offerLast(sample);
        }
        System.arraycopy(outputOverlap, HOP, outputOverlap, 0, FFT_SIZE - HOP);
        Arrays.fill(outputOverlap, FFT_SIZE - HOP, FFT_SIZE, 0f);
    }

    private static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = (inverse ? 2.0 : -2.0) * Math.PI / len;
            float wReStep = (float) Math.cos(ang);
            float wImStep = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wRe = 1f, wIm = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    float tRe = wRe * re[i + k + half] - wIm * im[i + k + half];
                    float tIm = wRe * im[i + k + half] + wIm * re[i + k + half];
                    re[i + k + half] = re[i + k] - tRe;
                    im[i + k + half] = im[i + k] - tIm;
                    re[i + k] += tRe;
                    im[i + k] += tIm;
                    float nwRe = wRe * wReStep - wIm * wImStep;
                    wIm = wRe * wImStep + wIm * wReStep;
                    wRe = nwRe;
                }
            }
        }
        if (inverse) {
            float inv = 1f / n;
            for (int i = 0; i < n; i++) { re[i] *= inv; im[i] *= inv; }
        }
    }
}
