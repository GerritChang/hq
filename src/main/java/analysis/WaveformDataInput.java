package analysis;

/**
 * Time-ordered waveform data input.
 *
 * <p>This demo implementation generates data on demand, so 20 x 25,000,000 samples do not need
 * to remain in memory. Replace {@link #valueAt(int, long)} with an indexed binary-file reader when
 * connecting measured data; the layout package does not need to change.</p>
 */
public final class WaveformDataInput {
    private final int channels;
    private final long samples;

    public WaveformDataInput(int channels, long samples) {
        if (channels <= 0 || samples <= 0) throw new IllegalArgumentException("channels and samples must be positive");
        this.channels = channels;
        this.samples = samples;
    }

    public int channelCount() { return channels; }

    public long sampleCount() { return samples; }

    public float valueAt(int channel, long sample) {
        if (channel < 0 || channel >= channels || sample < 0 || sample >= samples) {
            throw new IndexOutOfBoundsException("Invalid channel or sample index");
        }
        double t = sample * (0.000006 + channel * 0.00000073);
        double carrier = switch (channel % 4) {
            case 0 -> Math.sin(t);
            case 1 -> Math.sin(t) >= 0 ? 1 : -1;
            case 2 -> 2.0 * (t / (2 * Math.PI) - Math.floor(t / (2 * Math.PI) + 0.5));
            default -> Math.sin(t) * Math.sin(t * 0.031 + channel);
        };
        double amplitude = 0.48 + (channel % 5) * 0.08;
        return (float) (amplitude * carrier + 0.13 * Math.sin(t * (2.7 + channel * 0.11))
                + noise(channel, sample) * 0.07);
    }

    /** Returns min/max in the half-open sample range [from, to). */
    public void minMax(int channel, long from, long to, float[] result) {
        if (result == null || result.length < 2) throw new IllegalArgumentException("result needs two elements");
        long safeFrom = Math.max(0, from);
        long safeTo = Math.min(samples, Math.max(safeFrom + 1, to));
        long step = Math.max(1, (safeTo - safeFrom) / 24);
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (long sample = safeFrom; sample < safeTo; sample += step) {
            float value = valueAt(channel, sample);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        float last = valueAt(channel, safeTo - 1);
        result[0] = Math.min(min, last);
        result[1] = Math.max(max, last);
    }

    private static double noise(int channel, long sample) {
        long value = sample + channel * 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53 * 2 - 1;
    }
}
