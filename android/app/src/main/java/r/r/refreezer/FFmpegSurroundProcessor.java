package r.r.refreezer;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FFmpegSurroundProcessor
 *
 * Music-first FFmpegKit implementation:
 * Stereo input -> matrix-derived multi-channel -> AC3 encode
 *
 * Design goals:
 * - AC3-only output in the current pipeline
 * - Preserve original stereo character as much as possible
 * - Avoid fake ambience/reverb
 * - Rear channels are support channels, not cinematic gimmicks
 *
 * Preset routing strategy:
 * - RAW_CLONE        -> QUAD / 4.0
 * - ROOM_FILL_MATRIX -> QUAD / 4.0
 * - WIDE_STAGE       -> QUAD / 4.0
 * - IMMERSIVE_MUSIC  -> QUAD / 4.0
 * - VOCAL_ANCHOR     -> 5.0 (center allowed only here)
 *
 * Notes:
 * - Uses FFmpegKit bundled through local AAR
 * - No external ProcessBuilder-based ffmpeg binary required
 * - Constructor kept compatible with previous code paths
 */
public class FFmpegSurroundProcessor extends SurroundProcessor {

    private static final String TAG = "FFmpegSurroundProc";
    private static final double INV_SQRT2 = 0.7071067811865476d;

    /**
     * Kept only for compatibility with old constructor usage.
     * Not required by FFmpegKit execution path.
     */
    @SuppressWarnings("unused")
    private final String ffmpegBinaryPath;

    public FFmpegSurroundProcessor(String ffmpegBinaryPath) {
        this.ffmpegBinaryPath = ffmpegBinaryPath;
    }

    /**
     * With FFmpegKit linked in the app, availability is effectively true.
     */
    public boolean isAvailable() {
        return true;
    }

    /**
     * Main FFmpegKit-backed processing entrypoint.
     *
     * IMPORTANT:
     * - New pipeline is AC3 only
     * - TS has been intentionally removed for the music-first engine
     */
    public Result processWithFfmpeg(Config config) {
        try {
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

            if (!isAvailable()) {
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "AC3",
                        "ac3",
                        effectivePresetValue(config.preset),
                        0,
                        0,
                        "FFmpegKit not available"
                );
            }

            // AC3-only path. TS removed intentionally.
            if (config.outputMode == OutputMode.TS) {
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "AC3",
                        "ts",
                        effectivePresetValue(config.preset),
                        0,
                        0,
                        "TS export has been removed. Use AC3 output only."
                );
            }

            final boolean remoteInput = isRemoteInput(config.inputPath);
            File inputFile = new File(config.inputPath == null ? "" : config.inputPath);

            if (!remoteInput && (!inputFile.exists() || !inputFile.isFile())) {
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "AC3",
                        "ac3",
                        effectivePresetValue(config.preset),
                        0,
                        0,
                        "Input file not found"
                );
            }

            File finalOutputFile = new File(config.outputPath == null ? "" : config.outputPath);
            if (!ensureParentDirectory(finalOutputFile)) {
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        config.outputPath,
                        "AC3",
                        "ac3",
                        effectivePresetValue(config.preset),
                        remoteInput ? 0 : safeLength(inputFile),
                        0,
                        "Failed to create output directory"
                );
            }

            if (finalOutputFile.exists()) {
                if (config.overwrite) {
                    boolean deleted = finalOutputFile.delete();
                    if (!deleted) {
                        return Result.failure(
                                config.trackId,
                                config.inputPath,
                                config.outputPath,
                                "AC3",
                                "ac3",
                                effectivePresetValue(config.preset),
                                remoteInput ? 0 : safeLength(inputFile),
                                safeLength(finalOutputFile),
                                "Failed to overwrite existing output file"
                        );
                    }
                } else {
                    return Result.success(
                            config.trackId,
                            config.inputPath,
                            config.outputPath,
                            "AC3",
                            "ac3",
                            effectivePresetValue(config.preset),
                            remoteInput ? 0 : safeLength(inputFile),
                            safeLength(finalOutputFile),
                            "Output already exists"
                    );
                }
            }

            // Plumbing-only validation path
            if (config.debugPassthrough) {
                return super.process(config);
            }

            return renderToAc3Master(config, inputFile, finalOutputFile);

        } catch (Throwable t) {
            Log.e(TAG, "processWithFfmpeg crashed", t);
            return Result.failure(
                    config != null ? config.trackId : null,
                    config != null ? config.inputPath : null,
                    config != null ? config.outputPath : null,
                    "AC3",
                    "ac3",
                    config != null ? effectivePresetValue(config.preset) : "room_fill_matrix",
                    0,
                    0,
                    "FFmpegKit throwable: " + t.getClass().getName() + ": " + t.getMessage()
            );
        }
    }

    /**
     * Stereo -> music-first multi-channel -> AC3
     */
    @Override
    protected Result renderToAc3Master(Config config, File inputFile, File ac3OutputFile) {
        try {
            if (!ensureParentDirectory(ac3OutputFile)) {
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        ac3OutputFile.getAbsolutePath(),
                        "AC3",
                        "ac3",
                        effectivePresetValue(config.preset),
                        isRemoteInput(config.inputPath) ? 0 : safeLength(inputFile),
                        0,
                        "Failed to create AC3 output directory"
                );
            }

            String inputArg = resolveInputArg(config, inputFile);
            List<String> command = buildStereoToAc3Command(config, inputArg, ac3OutputFile);
            ProcessRunResult runResult = runCommand(command);

            if (runResult.exitCode != 0 || !ac3OutputFile.exists() || ac3OutputFile.length() <= 0) {
                return Result.failure(
                        config.trackId,
                        config.inputPath,
                        ac3OutputFile.getAbsolutePath(),
                        "AC3",
                        "ac3",
                        effectivePresetValue(config.preset),
                        isRemoteInput(config.inputPath) ? 0 : safeLength(inputFile),
                        safeLength(ac3OutputFile),
                        "AC3 render failed: " + trimForError(runResult.stderr)
                );
            }

            return Result.success(
                    config.trackId,
                    config.inputPath,
                    ac3OutputFile.getAbsolutePath(),
                    "AC3",
                    "ac3",
                    effectivePresetValue(config.preset),
                    isRemoteInput(config.inputPath) ? 0 : safeLength(inputFile),
                    safeLength(ac3OutputFile),
                    "AC3 music-first surround artifact generated"
            );

        } catch (Throwable t) {
            Log.e(TAG, "renderToAc3Master failed", t);
            return Result.failure(
                    config.trackId,
                    config.inputPath,
                    ac3OutputFile.getAbsolutePath(),
                    "AC3",
                    "ac3",
                    effectivePresetValue(config.preset),
                    isRemoteInput(config.inputPath) ? 0 : safeLength(inputFile),
                    safeLength(ac3OutputFile),
                    "AC3 render throwable: " + t.getClass().getName() + ": " + t.getMessage()
            );
        }
    }

    /**
     * TS intentionally removed from the new pipeline.
     */
    @Override
    protected Result muxAc3ToTs(Config config, File ac3InputFile, File tsOutputFile) {
        return Result.failure(
                config != null ? config.trackId : null,
                ac3InputFile != null ? ac3InputFile.getAbsolutePath() : null,
                tsOutputFile != null ? tsOutputFile.getAbsolutePath() : null,
                "AC3",
                "ts",
                effectivePresetValue(config != null ? config.preset : null),
                safeLength(ac3InputFile),
                safeLength(tsOutputFile),
                "TS output has been removed from the music-first surround pipeline"
        );
    }

    /**
     * Builds ffmpeg command for:
     * stereo -> music-first multi-channel -> AC3
     */
    protected List<String> buildStereoToAc3Command(
            Config config,
            String inputArg,
            File ac3OutputFile
    ) {
        List<String> cmd = new ArrayList<>();

        FilterProfile profile = FilterProfile.fromPreset(config.preset);

        int sampleRate = config.sampleRateHz > 0 ? config.sampleRateHz : 48000;
        int bitrateKbps = config.bitrateKbps > 0 ? config.bitrateKbps : 448;

        cmd.add("-y");

        cmd.add("-i");
        cmd.add(inputArg);

        cmd.add("-vn");
        cmd.add("-sn");
        cmd.add("-dn");

        cmd.add("-filter_complex");
        cmd.add(buildFilterComplex(config.preset));

        cmd.add("-map");
        cmd.add("[aout]");

        cmd.add("-ar");
        cmd.add(String.valueOf(sampleRate));

        cmd.add("-ac");
        cmd.add(String.valueOf(profile.outputChannelCount));

        cmd.add("-channel_layout");
        cmd.add(profile.outputChannelLayout);

        cmd.add("-c:a");
        cmd.add("ac3");

        cmd.add("-b:a");
        cmd.add(bitrateKbps + "k");

        cmd.add(ac3OutputFile.getAbsolutePath());

        return cmd;
    }

    /**
     * Build music-first FFmpeg filter graph.
     *
     * Layout policy:
     * - RAW_CLONE / ROOM_FILL / WIDE / IMMERSIVE = QUAD
     * - VOCAL_ANCHOR = 5.0
     */
    protected String buildFilterComplex(Preset preset) {
        FilterProfile profile = FilterProfile.fromPreset(preset);

        // Center and LFE use M = 0.707(L+R)
        double centerCoeff = profile.centerGain * INV_SQRT2;
        double lfeCoeff = profile.lfeGain * INV_SQRT2;

        // Side rears use S = 0.707(L-R)
        double rearCoeff = profile.rearGain * INV_SQRT2;

        // RAW_CLONE special case -> QUAD / 4.0
        if (profile.rawClone) {
            return "[0:a]aformat=channel_layouts=stereo,asplit=4[fls][frs][sls][srs];" +
                    "[fls]pan=mono|c0=" + f(profile.frontLeftL) + "*c0+" + f(profile.frontLeftR) + "*c1[fl];" +
                    "[frs]pan=mono|c0=" + f(profile.frontRightL) + "*c0+" + f(profile.frontRightR) + "*c1[fr];" +
                    "[sls]pan=mono|c0=" + f(profile.rearCloneLeftGain) + "*c0[sl];" +
                    "[srs]pan=mono|c0=" + f(profile.rearCloneRightGain) + "*c1[sr];" +
                    "[fl][fr][sl][sr]join=inputs=4:channel_layout=quad[aout]";
        }

        // QUAD presets: no center, no LFE
        if (profile.outputChannelLayout.equals("quad")) {
            StringBuilder sb = new StringBuilder();

            sb.append("[0:a]aformat=channel_layouts=stereo,asplit=4[fls][frs][sls][srs];");

            // Fronts
            sb.append("[fls]pan=mono|c0=")
                    .append(f(profile.frontLeftL)).append("*c0+")
                    .append(f(profile.frontLeftR)).append("*c1[fl];");

            sb.append("[frs]pan=mono|c0=")
                    .append(f(profile.frontRightL)).append("*c0+")
                    .append(f(profile.frontRightR)).append("*c1[fr];");

            // Rear left = +S
            sb.append("[sls]pan=mono|c0=")
                    .append(f(rearCoeff)).append("*c0-")
                    .append(f(rearCoeff)).append("*c1");

            if (profile.rearHighpassHz > 0) {
                sb.append(",highpass=f=").append(profile.rearHighpassHz);
            }
            if (profile.rearLowpassHz > 0) {
                sb.append(",lowpass=f=").append(profile.rearLowpassHz);
            }
            if (profile.rearDelayMs > 0) {
                sb.append(",adelay=").append(profile.rearDelayMs);
            }
            sb.append("[sl];");

            // Rear right = -S
            sb.append("[srs]pan=mono|c0=")
                    .append(f(-rearCoeff)).append("*c0+")
                    .append(f(rearCoeff)).append("*c1");

            if (profile.rearHighpassHz > 0) {
                sb.append(",highpass=f=").append(profile.rearHighpassHz);
            }
            if (profile.rearLowpassHz > 0) {
                sb.append(",lowpass=f=").append(profile.rearLowpassHz);
            }
            if (profile.rearDelayMs > 0) {
                sb.append(",adelay=").append(profile.rearDelayMs);
            }
            sb.append("[sr];");

            sb.append("[fl][fr][sl][sr]join=inputs=4:channel_layout=quad[aout]");

            return sb.toString();
        }

        // 5.0 preset path -> VOCAL_ANCHOR only
        StringBuilder sb = new StringBuilder();

        sb.append("[0:a]aformat=channel_layouts=stereo,asplit=5[fls][frs][fcs][sls][srs];");

        // Fronts
        sb.append("[fls]pan=mono|c0=")
                .append(f(profile.frontLeftL)).append("*c0+")
                .append(f(profile.frontLeftR)).append("*c1[fl];");

        sb.append("[frs]pan=mono|c0=")
                .append(f(profile.frontRightL)).append("*c0+")
                .append(f(profile.frontRightR)).append("*c1[fr];");

        // Center
        if (profile.centerGain > 0.0001d) {
            sb.append("[fcs]pan=mono|c0=")
                    .append(f(centerCoeff)).append("*c0+")
                    .append(f(centerCoeff)).append("*c1[fc];");
        } else {
            sb.append("[fcs]pan=mono|c0=0*c0+0*c1[fc];");
        }

        // Rear left = +S
        sb.append("[sls]pan=mono|c0=")
                .append(f(rearCoeff)).append("*c0-")
                .append(f(rearCoeff)).append("*c1");

        if (profile.rearHighpassHz > 0) {
            sb.append(",highpass=f=").append(profile.rearHighpassHz);
        }
        if (profile.rearLowpassHz > 0) {
            sb.append(",lowpass=f=").append(profile.rearLowpassHz);
        }
        if (profile.rearDelayMs > 0) {
            sb.append(",adelay=").append(profile.rearDelayMs);
        }
        sb.append("[sl];");

        // Rear right = -S
        sb.append("[srs]pan=mono|c0=")
                .append(f(-rearCoeff)).append("*c0+")
                .append(f(rearCoeff)).append("*c1");

        if (profile.rearHighpassHz > 0) {
            sb.append(",highpass=f=").append(profile.rearHighpassHz);
        }
        if (profile.rearLowpassHz > 0) {
            sb.append(",lowpass=f=").append(profile.rearLowpassHz);
        }
        if (profile.rearDelayMs > 0) {
            sb.append(",adelay=").append(profile.rearDelayMs);
        }
        sb.append("[sr];");

        sb.append("[fl][fr][fc][sl][sr]join=inputs=5:channel_layout=5.0[aout]");

        return sb.toString();
    }

    /**
     * Executes an FFmpegKit session from a list of arguments.
     */
    protected ProcessRunResult runCommand(List<String> command) {
        String commandString = joinCommand(command);
        Log.d(TAG, "Running FFmpegKit command: " + commandString);

        try {
            FFmpegSession session = FFmpegKit.execute(commandString);
            ReturnCode returnCode = session.getReturnCode();

            int exitCode = -1;
            if (returnCode != null) {
                exitCode = returnCode.getValue();
            }

            String output = "";
            try {
                String sessionOutput = session.getOutput();
                if (sessionOutput != null) {
                    output = sessionOutput;
                }
            } catch (Throwable ignored) {
            }

            if (exitCode == 0) {
                Log.d(TAG, "FFmpegKit exitCode=0");
                if (!output.trim().isEmpty()) {
                    Log.d(TAG, "FFmpegKit output:\n" + output);
                }
            } else {
                Log.e(TAG, "FFmpegKit exitCode=" + exitCode);
                if (!output.trim().isEmpty()) {
                    Log.e(TAG, "FFmpegKit failure output:\n" + output);
                }
            }

            return new ProcessRunResult(exitCode, output, output);
        } catch (Throwable t) {
            Log.e(TAG, "FFmpegKit execution crashed", t);
            return new ProcessRunResult(
                    -999,
                    "",
                    "FFmpegKit throwable: " + t.getClass().getName() + ": " + t.getMessage()
            );
        }
    }

    /**
     * Joins ffmpeg args into one FFmpegKit-friendly command string.
     * Quotes paths/args containing spaces or special chars.
     */
    protected String joinCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();

        for (String part : command) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(escapeArg(part));
        }

        return sb.toString();
    }

    protected String escapeArg(String arg) {
        if (arg == null) return "\"\"";

        boolean needsQuotes =
                arg.contains(" ") ||
                        arg.contains("(") ||
                        arg.contains(")") ||
                        arg.contains("[") ||
                        arg.contains("]") ||
                        arg.contains("{") ||
                        arg.contains("}") ||
                        arg.contains(";") ||
                        arg.contains(",") ||
                        arg.contains("&") ||
                        arg.contains("?") ||
                        arg.contains("=") ||
                        arg.contains("|");

        String escaped = arg.replace("\"", "\\\"");

        if (needsQuotes) {
            return "\"" + escaped + "\"";
        }

        return escaped;
    }

    protected String trimForError(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "unknown ffmpeg error";
        }
        input = input.trim();
        if (input.length() <= 1200) return input;
        return input.substring(Math.max(0, input.length() - 1200));
    }

    protected boolean ensureParentDirectory(File outputFile) {
        File parent = outputFile != null ? outputFile.getParentFile() : null;
        if (parent == null) return true;
        if (parent.exists()) return true;
        return parent.mkdirs();
    }

    private boolean isRemoteInput(String inputPath) {
        if (inputPath == null) return false;
        String v = inputPath.trim().toLowerCase();
        return v.startsWith("http://")
                || v.startsWith("https://")
                || v.startsWith("content://");
    }

    private String resolveInputArg(Config config, File inputFile) {
        if (isRemoteInput(config.inputPath)) {
            return config.inputPath;
        }
        return inputFile.getAbsolutePath();
    }

    protected static class ProcessRunResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ProcessRunResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static String f(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    /**
     * FilterProfile holds the effective music-first preset tuning.
     *
     * QUAD policy:
     * - raw_clone        -> quad
     * - room_fill_matrix -> quad
     * - wide_stage       -> quad
     * - immersive_music  -> quad
     *
     * 5.0 policy:
     * - vocal_anchor     -> 5.0
     *
     * Philosophy:
     * - Fronts preserved as much as possible
     * - No fake ambience/reverb
     * - Center only where intentionally desired
     */
    private static class FilterProfile {
        final boolean rawClone;

        final String outputChannelLayout;
        final int outputChannelCount;

        final double frontLeftL;
        final double frontLeftR;
        final double frontRightL;
        final double frontRightR;

        final double centerGain;      // multiplier applied to M
        final double rearGain;        // multiplier applied to S
        final double lfeGain;         // retained for compatibility, not used in QUAD/5.0 routes here

        final int rearDelayMs;
        final int rearHighpassHz;
        final int rearLowpassHz;
        final int lfeLowpassHz;

        final double rearCloneLeftGain;
        final double rearCloneRightGain;

        private FilterProfile(
                boolean rawClone,
                String outputChannelLayout,
                int outputChannelCount,
                double frontLeftL,
                double frontLeftR,
                double frontRightL,
                double frontRightR,
                double centerGain,
                double rearGain,
                double lfeGain,
                int rearDelayMs,
                int rearHighpassHz,
                int rearLowpassHz,
                int lfeLowpassHz,
                double rearCloneLeftGain,
                double rearCloneRightGain
        ) {
            this.rawClone = rawClone;
            this.outputChannelLayout = outputChannelLayout;
            this.outputChannelCount = outputChannelCount;
            this.frontLeftL = frontLeftL;
            this.frontLeftR = frontLeftR;
            this.frontRightL = frontRightL;
            this.frontRightR = frontRightR;
            this.centerGain = centerGain;
            this.rearGain = rearGain;
            this.lfeGain = lfeGain;
            this.rearDelayMs = rearDelayMs;
            this.rearHighpassHz = rearHighpassHz;
            this.rearLowpassHz = rearLowpassHz;
            this.lfeLowpassHz = lfeLowpassHz;
            this.rearCloneLeftGain = rearCloneLeftGain;
            this.rearCloneRightGain = rearCloneRightGain;
        }

        static FilterProfile fromPreset(Preset preset) {
            Preset canonical = preset != null ? preset.canonical() : Preset.ROOM_FILL_MATRIX;

            switch (canonical) {
                case RAW_CLONE:
                    // Pure Stereo:
                    // Front untouched
                    // Rear exact same-time stereo clones
                    // QUAD / 4.0
                    return new FilterProfile(
                            true,
                            "quad",
                            4,
                            1.000000, 0.000000,
                            0.000000, 1.000000,
                            0.00,
                            0.00,
                            0.00,
                            0,
                            0,
                            0,
                            80,
                            1.000000,
                            1.000000
                    );

                case WIDE_STAGE:
                    // QUAD / 4.0
                    // Slight width, very light rear support, no center
                    return new FilterProfile(
                            false,
                            "quad",
                            4,
                            0.98, -0.04,
                            -0.04, 0.98,
                            0.00,
                            0.08,
                            0.00,
                            5,
                            160,
                            6800,
                            80,
                            0.00,
                            0.00
                    );

                case VOCAL_ANCHOR:
                    // 5.0
                    // Only preset that intentionally uses center
                    return new FilterProfile(
                            false,
                            "5.0",
                            5,
                            1.00, 0.00,
                            0.00, 1.00,
                            0.18,
                            0.12,
                            0.00,
                            6,
                            140,
                            7000,
                            80,
                            0.00,
                            0.00
                    );

                case IMMERSIVE_MUSIC:
                    // QUAD / 4.0
                    // Bigger room feel, still no center to keep music-first front image
                    return new FilterProfile(
                            false,
                            "quad",
                            4,
                            0.99, 0.00,
                            0.00, 0.99,
                            0.00,
                            0.34,
                            0.00,
                            12,
                            120,
                            8500,
                            80,
                            0.00,
                            0.00
                    );

                case ROOM_FILL_MATRIX:
                default:
                    // QUAD / 4.0 flagship
                    // Front preserved, subtle rear support, no center
                    return new FilterProfile(
                            false,
                            "quad",
                            4,
                            1.00, 0.00,
                            0.00, 1.00,
                            0.00,
                            0.26,
                            0.00,
                            9,
                            140,
                            7200,
                            80,
                            0.00,
                            0.00
                    );
            }
        }
    }
}
