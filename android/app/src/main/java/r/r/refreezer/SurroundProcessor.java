package r.r.refreezer;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * SurroundProcessor
 *
 * Music-first surround processing abstraction:
 * - Primary artifact: AC3
 * - TS kept only as a legacy enum for compatibility, but no longer used by the
 *   music-first pipeline.
 *
 * Design goals:
 * - Preserve original stereo character
 * - Avoid fake ambience / fake reverb
 * - Keep front-stage integrity
 * - Allow richer preset model while staying backward-compatible
 */
public class SurroundProcessor {

    private static final String TAG = "SurroundProcessor";

    /**
     * Legacy container mode compatibility.
     *
     * IMPORTANT:
     * - AC3 is the supported output for the new music-first pipeline.
     * - TS is retained only to avoid breaking old method-channel / enum usages.
     */
    public enum OutputMode {
        AC3,
        TS
    }

    /**
     * Preset model:
     *
     * Legacy-compatible presets:
     * - BALANCED   -> maps to ROOM_FILL_MATRIX behavior
     * - WIDE       -> maps to WIDE_STAGE behavior
     * - CINEMATIC  -> maps to IMMERSIVE_MUSIC behavior
     *
     * New canonical presets:
     * - RAW_CLONE
     * - ROOM_FILL_MATRIX
     * - WIDE_STAGE
     * - VOCAL_ANCHOR
     * - IMMERSIVE_MUSIC
     */
    public enum Preset {
        BALANCED,
        WIDE,
        CINEMATIC,

        RAW_CLONE,
        ROOM_FILL_MATRIX,
        WIDE_STAGE,
        VOCAL_ANCHOR,
        IMMERSIVE_MUSIC;

        public static Preset fromString(String value) {
            if (value == null) return BALANCED;

            String normalized = value.trim().toLowerCase();

            switch (normalized) {
                case "raw":
                case "raw_clone":
                case "raw-stereo-clone":
                case "raw stereo clone":
                case "pure_stereo":
                case "pure stereo":
                    return RAW_CLONE;

                case "room_fill":
                case "room fill":
                case "room_fill_matrix":
                case "room fill matrix":
                case "natural_matrix":
                case "natural matrix":
                    return ROOM_FILL_MATRIX;

                case "wide_stage":
                case "wide stage":
                    return WIDE_STAGE;

                case "vocal_anchor":
                case "vocal anchor":
                case "vocal_focus":
                case "vocal focus":
                    return VOCAL_ANCHOR;

                case "immersive":
                case "immersive_music":
                case "immersive music":
                    return IMMERSIVE_MUSIC;

                // Legacy values
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
                case RAW_CLONE:
                    return "raw_clone";
                case ROOM_FILL_MATRIX:
                    return "room_fill_matrix";
                case WIDE_STAGE:
                    return "wide_stage";
                case VOCAL_ANCHOR:
                    return "vocal_anchor";
                case IMMERSIVE_MUSIC:
                    return "immersive_music";

                case WIDE:
                    return "wide";
                case CINEMATIC:
                    return "cinematic";
                case BALANCED:
                default:
                    return "balanced";
            }
        }

        /**
         * Canonical music-first preset mapping.
         * This keeps legacy enum names working without changing callers.
         */
        public Preset canonical() {
            switch (this) {
                case BALANCED:
                    return ROOM_FILL_MATRIX;
                case WIDE:
                    return WIDE_STAGE;
                case CINEMATIC:
                    return IMMERSIVE_MUSIC;
                default:
                    return this;
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
            private OutputMode outputMode = OutputMode.AC3;
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
                if (outputMode != null) {
                    this.outputMode = outputMode;
                }
                return this;
            }

            public Builder setPreset(Preset preset) {
                if (preset != null) {
                    this.preset = preset;
                }
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
                this.bitrateKbps = bitrateKbps > 0 ? bitrateKbps : 448;
                return this;
            }

            public Builder setSampleRateHz(int sampleRateHz) {
                this.sampleRateHz = sampleRateHz > 0 ? sampleRateHz : 48000;
                return this;
            }

            public Builder setOutputChannels(int outputChannels) {
                this.outputChannels = outputChannels > 0 ? outputChannels : 6;
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
     * - Returns placeholder failure for real surround generation
     *   (real implementation is in FFmpegSurroundProcessor)
     */
    public Result process(Config config) {
        if (config == null) {
            return Result.failure(
                    null,
                    null,
                    null,
                    "AC3",
                    "ac3",
                    "room_fill_matrix",
                    0,
                    0,
                    "Config is null"
            );
        }

        String presetValue = effectivePresetValue(config.preset);
        OutputMode outputMode = config.outputMode != null ? config.outputMode : OutputMode.AC3;

        File inputFile = new File(config.inputPath == null ? "" : config.inputPath);
        File outputFile = new File(config.outputPath == null ? "" : config.outputPath);

        if (!inputFile.exists() || !inputFile.isFile()) {
            return Result.failure(
                    config.trackId,
                    config.inputPath,
                    config.outputPath,
                    "AC3",
                    containerLabel(outputMode),
                    presetValue,
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
                    containerLabel(outputMode),
                    presetValue,
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
                            containerLabel(outputMode),
                            presetValue,
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
                        containerLabel(outputMode),
                        presetValue,
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
                        containerLabel(outputMode),
                        presetValue,
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
                        containerLabel(outputMode),
                        presetValue,
                        safeLength(inputFile),
                        safeLength(outputFile),
                        "Debug passthrough failed: " + e.getMessage()
                );
            }
        }

        return Result.failure(
                config.trackId,
                config.inputPath,
                config.outputPath,
                "AC3",
                containerLabel(outputMode),
                presetValue,
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
        String presetValue = effectivePresetValue(config != null ? config.preset : null);

        return Result.failure(
                config != null ? config.trackId : null,
                inputFile != null ? inputFile.getAbsolutePath() : null,
                ac3OutputFile != null ? ac3OutputFile.getAbsolutePath() : null,
                "AC3",
                "ac3",
                presetValue,
                safeLength(inputFile),
                safeLength(ac3OutputFile),
                "AC3 renderer not implemented yet"
        );
    }

    /**
     * Legacy hook retained for compatibility only.
     * The new music-first pipeline is AC3-only.
     */
    protected Result muxAc3ToTs(Config config, File ac3InputFile, File tsOutputFile) {
        String presetValue = effectivePresetValue(config != null ? config.preset : null);

        return Result.failure(
                config != null ? config.trackId : null,
                ac3InputFile != null ? ac3InputFile.getAbsolutePath() : null,
                tsOutputFile != null ? tsOutputFile.getAbsolutePath() : null,
                "AC3",
                "ts",
                presetValue,
                safeLength(ac3InputFile),
                safeLength(tsOutputFile),
                "TS output has been removed from the music-first surround pipeline"
        );
    }

    public static String containerLabel(OutputMode outputMode) {
        return outputMode == OutputMode.TS ? "ts" : "ac3";
    }

    public static String defaultExtension(OutputMode outputMode) {
        return outputMode == OutputMode.TS ? "ts" : "ac3";
    }

    /**
     * Useful path builder for output naming.
     */
    public static String buildOutputPath(String inputPath, String suffix, OutputMode mode) {
        return buildDerivedOutputPath(inputPath, suffix, defaultExtension(mode));
    }

    public static String buildPresetSuffix(Preset preset) {
        Preset safePreset = preset != null ? preset.canonical() : Preset.ROOM_FILL_MATRIX;
        return "surround_" + safePreset.value();
    }

    public static String effectivePresetValue(Preset preset) {
        Preset safePreset = preset != null ? preset.canonical() : Preset.ROOM_FILL_MATRIX;
        return safePreset.value();
    }

    /**
     * Self-contained replacement for Deezer.buildDerivedOutputPath(...)
     */
    private static String buildDerivedOutputPath(String inputPath, String suffix, String extension) {
        if (inputPath == null || inputPath.trim().isEmpty()) {
            String safeSuffix = (suffix == null || suffix.trim().isEmpty()) ? "output" : suffix.trim();
            String safeExt = normalizeExtension(extension);
            return safeSuffix + "." + safeExt;
        }

        File inputFile = new File(inputPath);
        String parent = inputFile.getParent();
        String name = inputFile.getName();

        String baseName = stripLastExtension(name);
        String safeSuffix = (suffix == null || suffix.trim().isEmpty()) ? "output" : suffix.trim();
        String safeExt = normalizeExtension(extension);

        String outputName = baseName + "_" + safeSuffix + "." + safeExt;

        if (parent == null || parent.trim().isEmpty()) {
            return outputName;
        }
        return new File(parent, outputName).getPath();
    }

    private static String stripLastExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "output";
        }

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return fileName;
        }
        return fileName.substring(0, lastDot);
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.trim().isEmpty()) {
            return "ac3";
        }

        String ext = extension.trim();
        while (ext.startsWith(".")) {
            ext = ext.substring(1);
        }

        if (ext.isEmpty()) {
            return "ac3";
        }
        return ext;
    }

    private static boolean ensureParentDirectory(File outputFile) {
        File parent = outputFile != null ? outputFile.getParentFile() : null;
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

    protected static long safeLength(File file) {
        if (file == null || !file.exists()) return 0;
        return file.length();
    }
}
