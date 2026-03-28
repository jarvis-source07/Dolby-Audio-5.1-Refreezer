package r.r.refreezer;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * FFmpegSurroundProcessor
 *
 * FFmpegKit-based implementation for:
 * Stereo input -> derived surround matrix -> AC3 encode -> optional TS mux
 *
 * IMPORTANT:
 * - This version uses FFmpegKit from the bundled/local AAR.
 * - No external ProcessBuilder-based ffmpeg binary execution is needed.
 * - Constructor is kept compatible with previous code paths.
 *
 * Recommended strategy:
 * - Master artifact = AC3
 * - Playback artifact = TS (for current app plumbing / compatibility)
 */
public class FFmpegSurroundProcessor extends SurroundProcessor {

    private static final String TAG = "FFmpegSurroundProc";

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
     * Kept simple and compatible with existing callers.
     */
    public boolean isAvailable() {
        return true;
    }

    /**
     * Main FFmpegKit-backed processing entrypoint.
     */
    public Result processWithFfmpeg(Config config) {
        if (config == null) {
            return Result.failure(
                    null,
                    null,
                    null,
                    "AC3",
                    "ts",
                    "balanced",
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
                    containerLabel(config.outputMode),
                    config.preset.value(),
                    0,
                    0,
                    "FFmpegKit not available"
            );
        }

        File inputFile = new File(config.inputPath == null ? "" : config.inputPath);
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

        File finalOutputFile = new File(config.outputPath == null ? "" : config.outputPath);
        if (!ensureParentDirectory(finalOutputFile)) {
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

        // Overwrite handling
        if (finalOutputFile.exists()) {
            if (config.overwrite) {
                boolean deleted = finalOutputFile.delete();
                if (!deleted) {
                    return Result.failure(
                            config.trackId,
                            config.inputPath,
                            config.outputPath,
                            "AC3",
                            containerLabel(config.outputMode),
                            config.preset.value(),
                            safeLength(inputFile),
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
                        containerLabel(config.outputMode),
                        config.preset.value(),
                        safeLength(inputFile),
                        safeLength(finalOutputFile),
                        "Output already exists"
                );
            }
        }

        // Optional plumbing-only validation path
        if (config.debugPassthrough) {
            return super.process(config);
        }

        // Create AC3 master path
        File ac3MasterFile;
        boolean deleteAc3AfterMux = false;

        if (config.outputMode == OutputMode.AC3) {
            ac3MasterFile = finalOutputFile;
        } else {
            String ac3Path = buildOutputPath(
                    config.inputPath,
                    buildPresetSuffix(config.preset) + "_master",
                    OutputMode.AC3
            );
            ac3MasterFile = new File(ac3Path);
            deleteAc3AfterMux = true;

            if (ac3MasterFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                ac3MasterFile.delete();
            }
        }

        Result ac3Result = renderToAc3Master(config, inputFile, ac3MasterFile);
        if (!ac3Result.success) {
            return ac3Result;
        }

        if (config.outputMode == OutputMode.AC3) {
            return ac3Result;
        }

        Result tsResult = muxAc3ToTs(config, ac3MasterFile, finalOutputFile);

        if (deleteAc3AfterMux && ac3MasterFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            ac3MasterFile.delete();
        }

        return tsResult;
    }

    /**
     * Step 1:
     * Stereo -> derived 5.1-style matrix -> AC3 encode
     */
    @Override
    protected Result renderToAc3Master(Config config, File inputFile, File ac3OutputFile) {
        try {
            if (!ensureParentDirectory(ac3OutputFile)) {
                return Result.failure(
                        config.trackId,
                        inputFile.getAbsolutePath(),
                        ac3OutputFile.getAbsolutePath(),
                        "AC3",
                        "ac3",
                        config.preset.value(),
                        safeLength(inputFile),
                        0,
                        "Failed to create AC3 output directory"
                );
            }

            List<String> command = buildStereoToAc3Command(config, inputFile, ac3OutputFile);
            ProcessRunResult runResult = runCommand(command);

            if (runResult.exitCode != 0 || !ac3OutputFile.exists()) {
                return Result.failure(
                        config.trackId,
                        inputFile.getAbsolutePath(),
                        ac3OutputFile.getAbsolutePath(),
                        "AC3",
                        "ac3",
                        config.preset.value(),
                        safeLength(inputFile),
                        safeLength(ac3OutputFile),
                        "AC3 render failed: " + trimForError(runResult.stderr)
                );
            }

            return Result.success(
                    config.trackId,
                    inputFile.getAbsolutePath(),
                    ac3OutputFile.getAbsolutePath(),
                    "AC3",
                    "ac3",
                    config.preset.value(),
                    safeLength(inputFile),
                    safeLength(ac3OutputFile),
                    "AC3 master generated"
            );

        } catch (Exception e) {
            Log.e(TAG, "renderToAc3Master failed", e);
            return Result.failure(
                    config.trackId,
                    inputFile.getAbsolutePath(),
                    ac3OutputFile.getAbsolutePath(),
                    "AC3",
                    "ac3",
                    config.preset.value(),
                    safeLength(inputFile),
                    safeLength(ac3OutputFile),
                    "AC3 render exception: " + e.getMessage()
            );
        }
    }

    /**
     * Step 2:
     * AC3 -> MPEG-TS
     */
    @Override
    protected Result muxAc3ToTs(Config config, File ac3InputFile, File tsOutputFile) {
        try {
            if (!ac3InputFile.exists()) {
                return Result.failure(
                        config.trackId,
                        ac3InputFile.getAbsolutePath(),
                        tsOutputFile.getAbsolutePath(),
                        "AC3",
                        "ts",
                        config.preset.value(),
                        0,
                        0,
                        "AC3 master file missing before TS mux"
                );
            }

            if (!ensureParentDirectory(tsOutputFile)) {
                return Result.failure(
                        config.trackId,
                        ac3InputFile.getAbsolutePath(),
                        tsOutputFile.getAbsolutePath(),
                        "AC3",
                        "ts",
                        config.preset.value(),
                        safeLength(ac3InputFile),
                        0,
                        "Failed to create TS output directory"
                );
            }

            List<String> command = buildAc3ToTsMuxCommand(ac3InputFile, tsOutputFile);
            ProcessRunResult runResult = runCommand(command);

            if (runResult.exitCode != 0 || !tsOutputFile.exists()) {
                return Result.failure(
                        config.trackId,
                        ac3InputFile.getAbsolutePath(),
                        tsOutputFile.getAbsolutePath(),
                        "AC3",
                        "ts",
                        config.preset.value(),
                        safeLength(ac3InputFile),
                        safeLength(tsOutputFile),
                        "TS mux failed: " + trimForError(runResult.stderr)
                );
            }

            return Result.success(
                    config.trackId,
                    ac3InputFile.getAbsolutePath(),
                    tsOutputFile.getAbsolutePath(),
                    "AC3",
                    "ts",
                    config.preset.value(),
                    safeLength(ac3InputFile),
                    safeLength(tsOutputFile),
                    "TS output generated"
            );

        } catch (Exception e) {
            Log.e(TAG, "muxAc3ToTs failed", e);
            return Result.failure(
                    config.trackId,
                    ac3InputFile.getAbsolutePath(),
                    tsOutputFile.getAbsolutePath(),
                    "AC3",
                    "ts",
                    config.preset.value(),
                    safeLength(ac3InputFile),
                    safeLength(tsOutputFile),
                    "TS mux exception: " + e.getMessage()
            );
        }
    }

    /**
     * Builds ffmpeg command for:
     * stereo -> matrix-derived 5.1 -> AC3
     *
     * This is a STARTER matrix:
     * - balanced front image
     * - subtle center
     * - modest LFE
     * - decorrelated-ish rear feel using cross subtraction
     *
     * You can tune this later.
     */
    protected List<String> buildStereoToAc3Command(
            Config config,
            File inputFile,
            File ac3OutputFile
    ) {
        List<String> cmd = new ArrayList<>();

        cmd.add("-y");

        cmd.add("-i");
        cmd.add(inputFile.getAbsolutePath());

        cmd.add("-vn");

        cmd.add("-af");
        cmd.add(buildPanFilter(config.preset));

        cmd.add("-ar");
        cmd.add(String.valueOf(config.sampleRateHz));

        cmd.add("-ac");
        cmd.add(String.valueOf(config.outputChannels));

        cmd.add("-c:a");
        cmd.add("ac3");

        cmd.add("-b:a");
        cmd.add(config.bitrateKbps + "k");

        cmd.add(ac3OutputFile.getAbsolutePath());

        return cmd;
    }

    /**
     * Builds ffmpeg command for:
     * AC3 -> MPEG-TS
     */
    protected List<String> buildAc3ToTsMuxCommand(File ac3InputFile, File tsOutputFile) {
        List<String> cmd = new ArrayList<>();

        cmd.add("-y");

        cmd.add("-i");
        cmd.add(ac3InputFile.getAbsolutePath());

        cmd.add("-c");
        cmd.add("copy");

        cmd.add("-f");
        cmd.add("mpegts");

        cmd.add(tsOutputFile.getAbsolutePath());

        return cmd;
    }

    /**
     * Starter surround matrix presets.
     * These are NOT final audiophile tunings.
     * They're just practical initial templates.
     */
    protected String buildPanFilter(Preset preset) {
        switch (preset) {
            case WIDE:
                return "pan=5.1|"
                        + "FL=0.95*c0+0.05*c1|"
                        + "FR=0.95*c1+0.05*c0|"
                        + "FC=0.22*c0+0.22*c1|"
                        + "LFE=0.18*c0+0.18*c1|"
                        + "SL=0.78*c0-0.22*c1|"
                        + "SR=0.78*c1-0.22*c0";

            case CINEMATIC:
                return "pan=5.1|"
                        + "FL=0.82*c0+0.18*c1|"
                        + "FR=0.82*c1+0.18*c0|"
                        + "FC=0.45*c0+0.45*c1|"
                        + "LFE=0.28*c0+0.28*c1|"
                        + "SL=0.70*c0-0.30*c1|"
                        + "SR=0.70*c1-0.30*c0";

            case BALANCED:
            default:
                return "pan=5.1|"
                        + "FL=0.90*c0+0.10*c1|"
                        + "FR=0.90*c1+0.10*c0|"
                        + "FC=0.35*c0+0.35*c1|"
                        + "LFE=0.20*c0+0.20*c1|"
                        + "SL=0.60*c0-0.20*c1|"
                        + "SR=0.60*c1-0.20*c0";
        }
    }

    /**
     * Executes an FFmpegKit session from a list of arguments.
     */
    protected ProcessRunResult runCommand(List<String> command) {
        String commandString = joinCommand(command);
        Log.d(TAG, "Running FFmpegKit command: " + commandString);

        FFmpegSession session = FFmpegKit.execute(commandString);
        ReturnCode returnCode = session.getReturnCode();

        int exitCode = -1;
        if (returnCode != null && returnCode.getValue() != null) {
            exitCode = returnCode.getValue();
        }

        String output = "";
        try {
            // Some FFmpegKit builds expose session output, some do not reliably separate streams.
            // We keep this guarded to stay compile/runtime-friendly.
            output = "";
        } catch (Exception ignored) {
        }

        Log.d(TAG, "FFmpegKit exitCode=" + exitCode);

        return new ProcessRunResult(exitCode, output, output);
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
                arg.contains(",");

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
        if (input.length() <= 1000) return input;
        return input.substring(input.length() - 1000);
    }

    protected boolean ensureParentDirectory(File outputFile) {
        File parent = outputFile.getParentFile();
        if (parent == null) return true;
        if (parent.exists()) return true;
        return parent.mkdirs();
    }

    protected long safeLength(File file) {
        if (file == null || !file.exists()) return 0;
        return file.length();
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
}
