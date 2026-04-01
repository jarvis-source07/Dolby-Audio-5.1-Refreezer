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
 * Stereo input -> matrix-derived 5.1 -> AC3 encode
 *
 * Design goals:
 * - AC3-only output in the new pipeline
 * - Preserve original stereo character
 * - Avoid fake ambience/reverb
 * - Rear channels are derived support channels, not cinematic gimmicks
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
     * Stereo -> music-first 5.1 -> AC3
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
     * stereo -> matrix-derived 5.1 -> AC3
     *
     * Uses -filter_complex rather than simple pan-only routing so we can:
     * - derive rear side information
     * - band-limit rears
     * - add precise rear delay
     * - keep center subtle
     * - keep optional LFE filtered only
     */
    protected List<String> buildStereoToAc3Command(
            Config config,
            String inputArg,
            File ac3OutputFile
    ) {
        List<String> cmd = new ArrayList<>();

        int sampleRate = config.sampleRateHz > 0 ? config.sampleRateHz : 48000;
        int channels = config.outputChannels > 0 ? config.outputChannels : 6;
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
        cmd.add(String.valueOf(channels));

        cmd.add("-channel_layout");
        cmd.add("5.1");

        cmd.add("-c:a");
        cmd.add("ac3");

        cmd.add("-b:a");
        cmd.add(bitrateKbps + "k");

        cmd.add(ac3OutputFile.getAbsolutePath());

        return cmd;
    }

    /**
     * Build music-first FFmpeg filter graph.
     */
    protected String buildFilterComplex(Preset preset) {
        FilterProfile profile = FilterProfile.fromPreset(preset);

        // Center and LFE use M = 0.707(L+R)
        double centerCoeff = profile.centerGain * INV_SQRT2;
        double lfeCoeff = profile.lfeGain * INV_SQRT2;

        // Side rears use S = 0.707(L-R)
        double rearCoeff = profile.rearGain * INV_SQRT2;

        // Raw clone special-case: direct stereo copy in rears
        if (profile.rawClone) {
            return "[0:a]aformat=channel_layouts=stereo,asplit=6[fls][frs][fcs][lfes][sls][srs];" +
                    "[fls]pan=mono|c0=" + f(profile.frontLeftL) + "*c0+" + f(profile.frontLeftR) + "*c1[fl];" +
                    "[frs]pan=mono|c0=" + f(profile.frontRightL) + "*c0+" + f(profile.frontRightR) + "*c1[fr];" +
                    "[fcs]pan=mono|c0=0*c0+0*c1[fc];" +
                    "[lfes]pan=mono|c0=0*c0+0*c1[lfe];" +
                    "[sls]pan=mono|c0=" + f(profile.rearCloneLeftGain) + "*c0[sl];" +
                    "[srs]pan=mono|c0=" + f(profile.rearCloneRightGain) + "*c1[sr];" +
                    "[fl][fr][fc][lfe][sl][sr]join=inputs=6:channel_layout=5.1[aout]";
        }

        StringBuilder sb = new StringBuilder();

        sb.append("[0:a]aformat=channel_layouts=stereo,asplit=6[fls][frs][fcs][lfes][sls][srs];");

        // Fronts
        sb.append("[fls]pan=mono|c0=")
                .append(f(profile.frontLeftL)).append("*c0+")
                .append(f(profile.frontLeftR)).append("*c1[fl];");

        sb.append("[frs]pan=mono|c0=")
                .append(f(profile.frontRightL)).append("*c0+")
                .append(f(profile.frontRightR)).append("*c1[fr];");

        // Center (subtle and optional)
        if (profile.centerGain > 0.0001d) {
            sb.append("[fcs]pan=mono|c0=")
                    .append(f(centerCoeff)).append("*c0+")
                    .append(f(centerCoeff)).append("*c1[fc];");
        } else {
            sb.append("[fcs]pan=mono|c0=0*c0+0*c1[fc];");
        }

        // LFE (filtered only, optional)
        if (profile.lfeGain > 0.0001d) {
            sb.append("[lfes]pan=mono|c0=")
                    .append(f(lfeCoeff)).append("*c0+")
                    .append(f(lfeCoeff)).append("*c1,")
                    .append("lowpass=f=").append(profile.lfeLowpassHz)
                    .append("[lfe];");
        } else {
            sb.append("[lfes]pan=mono|c0=0*c0+0*c1[lfe];");
        }

        // Rears: ±S with delay and band-limiting
        sb.append("[sls]pan=mono|c0=")
                .append(f(rearCoeff)).append("*c0")
                .append(rearCoeff >= 0 ? "-" : "+")
                .append(f(Math.abs(rearCoeff))).append("*c1");

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

        sb.append("[fl][fr][fc][lfe][sl][sr]join=inputs=6:channel_layout=5.1[aout]");

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
     * This is intentionally conservative:
     * - fronts stay dominant
     * - center stays subtle
     * - rears are derived support channels
     * - no fake ambience/reverb
     */
    private static class FilterProfile {
        final boolean rawClone;

        final double frontLeftL;
        final double frontLeftR;
        final double frontRightL;
        final double frontRightR;

        final double centerGain;      // multiplier applied to M
        final double rearGain;        // multiplier applied to S
        final double lfeGain;         // multiplier applied to M (after LPF)

        final int rearDelayMs;
        final int rearHighpassHz;
        final int rearLowpassHz;
        final int lfeLowpassHz;

        final double rearCloneLeftGain;
        final double rearCloneRightGain;

        private FilterProfile(
                boolean rawClone,
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
                    return new FilterProfile(
                            true,
                            1.000000, 0.000000,
                            0.000000, 1.000000,
                            0.0,
                            0.0,
                            0.0,
                            0,
                            0,
                            0,
                            80,
                            0.92,
                            0.92
                    );

                case WIDE_STAGE:
                    // Front widen:
                    // L' = M + 1.15S
                    // R' = M - 1.15S
                    // Expanded to L/R coefficients:
                    // L' = 0.707*(1+1.15)L + 0.707*(1-1.15)R
                    // R' = 0.707*(1-1.15)L + 0.707*(1+1.15)R
                    return new FilterProfile(
                            false,
                            0.760140, -0.106066,
                            -0.106066, 0.760140,
                            0.0,
                            0.12,
                            0.0,
                            6,
                            150,
                            7000,
                            80,
                            0.0,
                            0.0
                    );

                case VOCAL_ANCHOR:
                    return new FilterProfile(
                            false,
                            0.92, 0.00,
                            0.00, 0.92,
                            0.28,
                            0.18,
                            0.15,
                            8,
                            120,
                            7500,
                            80,
                            0.0,
                            0.0
                    );

                case IMMERSIVE_MUSIC:
                    return new FilterProfile(
                            false,
                            0.95, 0.00,
                            0.00, 0.95,
                            0.18,
                            0.45,
                            0.25,
                            15,
                            110,
                            9000,
                            80,
                            0.0,
                            0.0
                    );

                case ROOM_FILL_MATRIX:
                default:
                    return new FilterProfile(
                            false,
                            0.96, 0.00,
                            0.00, 0.96,
                            0.15,
                            0.35,
                            0.20,
                            12,
                            120,
                            8000,
                            80,
                            0.0,
                            0.0
                    );
            }
        }
    }
}
