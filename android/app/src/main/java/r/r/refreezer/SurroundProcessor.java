package r.r.refreezer;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * SurroundProcessor
 *
 * Starter processing layer for future:
 *  Stereo source -> derived surround -> AC3 -> optional TS mux
 *
 * IMPORTANT:
 * - This is a compile-safe starter.
 * - It does NOT perform real surround rendering yet.
 * - It provides the structure where actual encoder / mux logic will be added.
 */
public class SurroundProcessor {

    private static final String TAG = "SurroundProcessor";

    public enum OutputMode {
        AC3,
        TS
    }

    public enum Preset {
        BALANCED,
        WIDE,
        CINEMATIC;

        public static Preset fromString(String value) {
            if (value == null) return BALANCED;

            switch (value.trim().toLowerCase()) {
                case "wide":
                    return WIDE;
                case "cinematic":
                    return CINEMATIC;
                case "balanced":
                default:
                    return BALANCED;
            }
        }

        public String value() {
            switch (this) {
                case WIDE:
                    return "wide";
                case CINEMATIC:
                    return "cinematic";
                case BALANCED:
                default:
                    return "balanced";
            }
        }
    }

    public static class Config {
        public final String trackId;
        public final String inputPath;
        public final String outputPath;
        public final OutputMode outputMode;
        public final Preset preset;
        public final boolean overwrite;
        public final boolean debugPassthrough;
        public final int bitrateKbps;
        public final int sampleRateHz;
        public final int outputChannels;

        private Config(Builder builder) {
            this.trackId = builder.trackId;
            this.inputPath = builder.inputPath;
            this.outputPath = builder.outputPath;
            this.outputMode = builder.outputMode;
            this.preset = builder.preset;
            this.overwrite = builder.overwrite;
            this.debugPassthrough = builder.debugPassthrough;
            this.bitrateKbps = builder.bitrateKbps;
            this.sampleRateHz = builder.sampleRateHz;
            this.outputChannels = builder.outputChannels;
        }

        public static class Builder {
            private String trackId;
            private String inputPath;
            private String outputPath;
            private OutputMode outputMode = OutputMode.TS;
            private Preset preset = Preset.BALANCED;
            private boolean overwrite = true;
            private boolean debugPassthrough = false;
            private int bitrateKbps = 448;
            private int sampleRateHz = 48000;
            private int outputChannels = 6;

            public Builder setTrackId(String trackId) {
                this.trackId = trackId;
                return this;
            }

            public Builder setInputPath(String inputPath) {
                this.inputPath = inputPath;
                return this;
            }

            public Builder setOutputPath(String outputPath) {
                this.outputPath = outputPath;
                return this;
            }

            public Builder setOutputMode(OutputMode outputMode) {
                this.outputMode = outputMode;
                return this;
            }

            public Builder setPreset(Preset preset) {
                this.preset = preset;
                return this;
            }

            public Builder setOverwrite(boolean overwrite) {
                this.overwrite = overwrite;
                return this;
            }

            public Builder setDebugPassthrough(boolean debugPassthrough) {
                this.debugPassthrough = debugPassthrough;
                return this;
            }

            public Builder setBitrateKbps(int bitrateKbps) {
                this.bitrateKbps = bitrateKbps;
                return this;
            }

            public Builder setSampleRateHz(int sampleRateHz) {
                this.sampleRateHz = sampleRateHz;
                return this;
            }

            public Builder setOutputChannels(int outputChannels) {
                this.outputChannels = outputChannels;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    public static class Result {
        public final boolean success;
        public final String trackId;
        public final String inputPath;
        public final String outputPath;
        public final String codec;
        public final String container;
        public final String preset;
        public final long inputBytes;
        public final long outputBytes;
        public final String message;
        public final String error;

        private Result(
                boolean success,
                String trackId,
                String inputPath,
                String outputPath,
                String codec,
                String container,
                String preset,
                long inputBytes,
                long outputBytes,
                String message,
                String error
        ) {
            this.success = success;
            this.trackId = trackId;
            this.inputPath = inputPath;
            this.outputPath = outputPath;
            this.codec = codec;
            this.container = container;
            this.preset = preset;
            this.inputBytes = inputBytes;
            this.outputBytes = outputBytes;
            this.message = message;
            this.error = error;
        }

        public static Result success(
                String trackId,
                String inputPath,
                String outputPath,
                String codec,
                String container,
                String preset,
                long inputBytes,
                long outputBytes,
                String message
        ) {
            return new Result(
                    true,
                    trackId,
                    inputPath,
                    outputPath,
                    codec,
                    container,
                    preset,
                    inputBytes,
                    outputBytes,
                    message,
                    null
            );
        }

        public static Result failure(
                String trackId,
                String inputPath,
                String outputPath,
                String codec,
                String container,
                String preset,
                long inputBytes,
                long outputBytes,
                String error
        ) {
            return new Result(
                    false,
                    trackId,
                    inputPath,
                    outputPath,
                    codec,
                    container,
                    preset,
                    inputBytes,
                    outputBytes,
                    null,
                    error
            );
        }

        @Override
        public String toString() {
            return "Result{" +
                    "success=" + success +
                    ", trackId='" + trackId + '\'' +
                    ", inputPath='" + inputPath + '\'' +
                    ", outputPath='" + outputPath + '\'' +
                    ", codec='" + codec + '\'' +
                    ", container='" + container + '\'' +
                    ", preset='" + preset + '\'' +
                    ", inputBytes=" + inputBytes +
                    ", outputBytes=" + outputBytes +
                    ", message='" + message + '\'' +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    /**
     * Main starter entrypoint.
     *
     * Current behavior:
     * - Validates input/output
     * - Supports debug passthrough copy
     * - Returns placeholder failure for real surround generation (until encoder is wired)
     */
    public Result process(Config config) {
        if (config == null) {
            return Result.failure(
                    null, null, null,
                    "AC3",
                    "ts",
                    "balanced",
                    0, 0,
                    "Config is null"
            );
        }

        File inputFile = new File(config.inputPath == null ? "" : config.inputPath);
        File outputFile = new File(config.outputPath == null ? "" : config.outputPath);

        if (!inputFile.exists() || !inputFile.isFile()) {
            return Result.failure(
                    config.trackId,
                    config.inputPath,
                    config.outputPath,
                    "AC3",
                    containerLabel(config.outputMode),
                    config.preset.value(),
                    0,
                    0,
                    "Input file not found"
            );
        }

        if (!ensureParentDirectory(outputFile)) {
            return Result.failure(
                    config.trackId,
                    config.inputPath,
                    config.outputPath,
                    "AC3",
                    containerLabel(config.outputMode),
                    config.preset.value(),
                    safeLength(inputFile),
                    0,
                    "Failed to create output directory"
            );
        }

        if (outputFile.exists()) {
            if (config.overwrite) {
                boolean deleted = outputFile.delete();
                if (!deleted) {
                    return Result.failure(
                            config.trackId,
                            config.inputPath,
                            config.outputPath,
                            "AC3",
                            containerLabel(config.outputMode),
                            config.preset.value(),
                            safeLength(inputFile),
                            safeLength(outputFile),
                            "Failed to overwrite existing output file"
                    );
                }
            } else {
                return Result.success(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "AC3",
                        containerLabel(config.outputMode),
                        config.preset.value(),
                        safeLength(inputFile),
                        safeLength(outputFile),
                        "Output already exists"
                );
            }
        }

        // Debug path: just copy input to output to validate file plumbing
        if (config.debugPassthrough) {
            try {
                copyFile(inputFile, outputFile);
                return Result.success(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "PASSTHROUGH",
                        containerLabel(config.outputMode),
                        config.preset.value(),
                        safeLength(inputFile),
                        safeLength(outputFile),
                        "Debug passthrough copy complete"
                );
            } catch (Exception e) {
                Log.e(TAG, "Debug passthrough failed", e);
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "PASSTHROUGH",
                        containerLabel(config.outputMode),
                        config.preset.value(),
                        safeLength(inputFile),
                        safeLength(outputFile),
                        "Debug passthrough failed: " + e.getMessage()
                );
            }
        }

        // Real future route:
        // 1) decode / inspect stereo input
        // 2) render derived surround bed according to preset
        // 3) encode AC3 master artifact
        // 4) if outputMode == TS => mux AC3 into TS
        //
        // For now return placeholder failure so behavior is honest.
        return Result.failure(
                config.trackId,
                config.inputPath,
                config.outputPath,
                "AC3",
                containerLabel(config.outputMode),
                config.preset.value(),
                safeLength(inputFile),
                0,
                "Surround engine backend not connected yet"
        );
    }

    /**
     * Future hook:
     * Stereo decode + matrix render + AC3 encode
     */
    protected Result renderToAc3Master(Config config, File inputFile, File ac3OutputFile) {
        return Result.failure(
                config.trackId,
                inputFile.getAbsolutePath(),
                ac3OutputFile.getAbsolutePath(),
                "AC3",
                "ac3",
                config.preset.value(),
                safeLength(inputFile),
                safeLength(ac3OutputFile),
                "AC3 renderer not implemented yet"
        );
    }

    /**
     * Future hook:
     * Mux already encoded AC3 into MPEG-TS container
     */
    protected Result muxAc3ToTs(Config config, File ac3InputFile, File tsOutputFile) {
        return Result.failure(
                config.trackId,
                ac3InputFile.getAbsolutePath(),
                tsOutputFile.getAbsolutePath(),
                "AC3",
                "ts",
                config.preset.value(),
                safeLength(ac3InputFile),
                safeLength(tsOutputFile),
                "TS muxer not implemented yet"
        );
    }

    public static String containerLabel(OutputMode outputMode) {
        return outputMode == OutputMode.AC3 ? "ac3" : "ts";
    }

    public static String defaultExtension(OutputMode outputMode) {
        return outputMode == OutputMode.AC3 ? "ac3" : "ts";
    }

    /**
     * Useful path builder for future output naming.
     *
     * Example:
     * input = /music/song.flac
     * suffix = surround_balanced
     * mode = TS
     * => /music/song_surround_balanced.ts
     */
    public static String buildOutputPath(
            String inputPath,
            String suffix,
            OutputMode mode
    ) {
        return Deezer.buildDerivedOutputPath(
                inputPath,
                suffix,
                defaultExtension(mode)
        );
    }

    public static String buildPresetSuffix(Preset preset) {
        return "surround_" + preset.value();
    }

    private static boolean ensureParentDirectory(File outputFile) {
        File parent = outputFile.getParentFile();
        if (parent == null) return true;
        if (parent.exists()) return true;
        return parent.mkdirs();
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {

            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
        }
    }

    private static long safeLength(File file) {
        if (file == null || !file.exists()) return 0;
        return file.length();
    }
}