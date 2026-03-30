package r.r.refreezer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

/**
 * Base abstraction for stereo -> surround artifact processing.
 *
 * Subclasses (for example FFmpegSurroundProcessor) implement actual rendering/muxing.
 */
public abstract class SurroundProcessor {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    public enum OutputMode {
        AC3("ac3"),
        TS("ts");

        private final String extension;

        OutputMode(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }

        public static OutputMode fromString(@Nullable String raw) {
            if (raw == null) return AC3;

            String v = raw.trim().toLowerCase(Locale.US);
            if ("ts".equals(v) || "mpegts".equals(v)) {
                return TS;
            }
            return AC3;
        }
    }

    public enum Preset {
        BALANCED("balanced"),
        WIDE("wide"),
        CINEMATIC("cinematic");

        private final String value;

        Preset(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Preset fromString(@Nullable String raw) {
            if (raw == null) return BALANCED;

            String v = raw.trim().toLowerCase(Locale.US);
            switch (v) {
                case "wide":
                    return WIDE;
                case "cinematic":
                    return CINEMATIC;
                case "balanced":
                default:
                    return BALANCED;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    public static class Config {
        @Nullable public final String trackId;
        @Nullable public final String inputPath;
        @Nullable public final String outputPath;
        @NonNull public final OutputMode outputMode;
        @NonNull public final Preset preset;
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
            @Nullable private String trackId;
            @Nullable private String inputPath;
            @Nullable private String outputPath;
            @NonNull private OutputMode outputMode = OutputMode.AC3;
            @NonNull private Preset preset = Preset.BALANCED;
            private boolean overwrite = false;
            private boolean debugPassthrough = false;
            private int bitrateKbps = 448;
            private int sampleRateHz = 48000;
            private int outputChannels = 6;

            public Builder setTrackId(@Nullable String trackId) {
                this.trackId = trackId;
                return this;
            }

            public Builder setInputPath(@Nullable String inputPath) {
                this.inputPath = inputPath;
                return this;
            }

            public Builder setOutputPath(@Nullable String outputPath) {
                this.outputPath = outputPath;
                return this;
            }

            public Builder setOutputMode(@NonNull OutputMode outputMode) {
                this.outputMode = outputMode;
                return this;
            }

            public Builder setPreset(@NonNull Preset preset) {
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

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    public static class Result {
        public final boolean success;
        @Nullable public final String trackId;
        @Nullable public final String inputPath;
        @Nullable public final String outputPath;
        @NonNull public final String codec;
        @NonNull public final String container;
        @NonNull public final String preset;
        public final long inputBytes;
        public final long outputBytes;
        @Nullable public final String message;

        private Result(
                boolean success,
                @Nullable String trackId,
                @Nullable String inputPath,
                @Nullable String outputPath,
                @NonNull String codec,
                @NonNull String container,
                @NonNull String preset,
                long inputBytes,
                long outputBytes,
                @Nullable String message
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
        }

        public static Result success(
                @Nullable String trackId,
                @Nullable String inputPath,
                @Nullable String outputPath,
                @NonNull String codec,
                @NonNull String container,
                @NonNull String preset,
                long inputBytes,
                long outputBytes,
                @Nullable String message
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
                    message
            );
        }

        public static Result failure(
                @Nullable String trackId,
                @Nullable String inputPath,
                @Nullable String outputPath,
                @NonNull String codec,
                @NonNull String container,
                @NonNull String preset,
                long inputBytes,
                long outputBytes,
                @Nullable String message
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
                    message
            );
        }
    }

    // -------------------------------------------------------------------------
    // Base process flow
    // -------------------------------------------------------------------------

    /**
     * Generic processing flow:
     * - If AC3 output requested -> render AC3 master directly
     * - If TS requested -> render temp AC3 master, then mux to TS
     *
     * Subclasses can override if they want custom handling.
     */
    public Result process(@Nullable Config config) {
        if (config == null) {
            return Result.failure(
                    null,
                    null,
                    null,
                    "AC3",
                    "ac3",
                    "balanced",
                    0,
                    0,
                    "Config is null"
            );
        }

        if (config.inputPath == null || config.inputPath.trim().isEmpty()) {
            return Result.failure(
                    config.trackId,
                    null,
                    config.outputPath,
                    "AC3",
                    containerLabel(config.outputMode),
                    config.preset.value(),
                    0,
                    0,
                    "Input path is empty"
            );
        }

        String finalOutputPath = config.outputPath;
        if (finalOutputPath == null || finalOutputPath.trim().isEmpty()) {
            finalOutputPath = buildOutputPath(
                    config.inputPath,
                    buildPresetSuffix(config.preset),
                    config.outputMode
            );
        }

        File inputFile = new File(config.inputPath);
        File finalOutputFile = new File(finalOutputPath);

        if (config.outputMode == OutputMode.AC3) {
            return renderToAc3Master(config, inputFile, finalOutputFile);
        }

        // TS mode => render temp AC3 first
        File ac3Temp = new File(
                buildOutputPath(
                        config.inputPath,
                        buildPresetSuffix(config.preset) + "_master",
                        OutputMode.AC3
                )
        );

        Result ac3Result = renderToAc3Master(config, inputFile, ac3Temp);
        if (!ac3Result.success) {
            return ac3Result;
        }

        Result tsResult = muxAc3ToTs(config, ac3Temp, finalOutputFile);

        if (ac3Temp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            ac3Temp.delete();
        }

        return tsResult;
    }

    // -------------------------------------------------------------------------
    // Helpers for subclasses
    // -------------------------------------------------------------------------

    protected String buildPresetSuffix(@Nullable Preset preset) {
        return preset == null ? "balanced" : preset.value();
    }

    protected String containerLabel(@Nullable OutputMode outputMode) {
        return outputMode == OutputMode.TS ? "ts" : "ac3";
    }

    protected String buildOutputPath(
            @Nullable String inputPath,
            @Nullable String suffix,
            @NonNull OutputMode outputMode
    ) {
        String baseName = "surround_output";

        if (inputPath != null && !inputPath.trim().isEmpty()) {
            try {
                String clean = inputPath;

                int queryIndex = clean.indexOf('?');
                if (queryIndex >= 0) {
                    clean = clean.substring(0, queryIndex);
                }

                int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf(File.separatorChar));
                if (slash >= 0 && slash + 1 < clean.length()) {
                    clean = clean.substring(slash + 1);
                }

                int dot = clean.lastIndexOf('.');
                if (dot > 0) {
                    clean = clean.substring(0, dot);
                }

                clean = clean.trim();
                if (!clean.isEmpty()) {
                    baseName = sanitizeFileComponent(clean);
                }
            } catch (Throwable ignored) {
            }
        }

        String safeSuffix = (suffix == null || suffix.trim().isEmpty())
                ? ""
                : "_" + sanitizeFileComponent(suffix.trim());

        String extension = outputMode.extension();

        String parentDir;
        if (inputPath != null &&
                !inputPath.startsWith("http://") &&
                !inputPath.startsWith("https://") &&
                !inputPath.startsWith("content://")) {
            File inputFile = new File(inputPath);
            File parent = inputFile.getParentFile();
            parentDir = (parent != null) ? parent.getAbsolutePath() : ".";
        } else {
            parentDir = ".";
        }

        return new File(parentDir, baseName + safeSuffix + "." + extension).getAbsolutePath();
    }

    protected String sanitizeFileComponent(@NonNull String input) {
        String sanitized = input.replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitized = sanitized.replaceAll("\\s+", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+", "");
        sanitized = sanitized.replaceAll("_+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "file";
        }
        return sanitized;
    }

    // -------------------------------------------------------------------------
    // Abstract hooks implemented by subclasses
    // -------------------------------------------------------------------------

    protected abstract Result renderToAc3Master(
            Config config,
            File inputFile,
            File ac3OutputFile
    );

    protected abstract Result muxAc3ToTs(
            Config config,
            File ac3InputFile,
            File tsOutputFile
    );
}
