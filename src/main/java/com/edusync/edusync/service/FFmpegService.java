package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
public class FFmpegService {

    private static final String FFMPEG =
            "C:\\Users\\Admin\\AppData\\Local\\Microsoft\\WinGet\\Packages\\"
            + "Gyan.FFmpeg.Shared_Microsoft.Winget.Source_8wekyb3d8bbwe\\"
            + "ffmpeg-9.0.2-full_build-shared\\bin\\ffmpeg.exe";

    public String createVideo(int numberOfSlides) {

        try {

            File outputDirectory = new File("generated-slides");

            if (!outputDirectory.exists()) {
                throw new RuntimeException(
                        "generated-slides folder not found: "
                                + outputDirectory.getAbsolutePath()
                );
            }

            File ffmpegFile = new File(FFMPEG);

            if (!ffmpegFile.exists()) {
                throw new RuntimeException(
                        "FFmpeg not found at: " + FFMPEG
                );
            }

            /*
             * ----------------------------------------------------
             * STEP 1: CREATE SMALL MP4 FOR EACH SLIDE
             * ----------------------------------------------------
             */

            List<File> segments = new ArrayList<>();

            for (int i = 1; i <= numberOfSlides; i++) {

                File image = new File(
                        outputDirectory,
                        "slide-" + i + ".png"
                );

                File audio = new File(
                        outputDirectory,
                        "narration-" + i + ".wav"
                );

                if (!image.exists()) {
                    throw new RuntimeException(
                            "Missing slide: "
                                    + image.getAbsolutePath()
                    );
                }

                if (!audio.exists()) {
                    throw new RuntimeException(
                            "Missing narration: "
                                    + audio.getAbsolutePath()
                    );
                }

                File segment = new File(
                        outputDirectory,
                        "segment-" + i + ".mp4"
                );

                System.out.println(
                        "Creating video segment "
                                + i + " / "
                                + numberOfSlides
                );

                List<String> command = new ArrayList<>();

                command.add(FFMPEG);

                // Loop the image
                command.add("-loop");
                command.add("1");

                command.add("-i");
                command.add(image.getAbsolutePath());

                // Narration
                command.add("-i");
                command.add(audio.getAbsolutePath());

                // Video settings
                command.add("-c:v");
                command.add("libx264");

                command.add("-preset");
                command.add("veryfast");

                command.add("-tune");
                command.add("stillimage");

                command.add("-pix_fmt");
                command.add("yuv420p");

                command.add("-vf");
                command.add("scale=1280:720,setsar=1");

                // Audio settings
                command.add("-c:a");
                command.add("aac");

                command.add("-b:a");
                command.add("128k");

                /*
                 * VERY IMPORTANT:
                 *
                 * -shortest makes the video stop when
                 * narration finishes.
                 */
                command.add("-shortest");

                command.add("-y");

                command.add(segment.getAbsolutePath());

                ProcessBuilder processBuilder =
                        new ProcessBuilder(command);

                processBuilder.inheritIO();

                Process process =
                        processBuilder.start();

                int exitCode =
                        process.waitFor();

                if (exitCode != 0) {

                    throw new RuntimeException(
                            "FFmpeg failed while creating segment "
                                    + i
                                    + ". Exit code: "
                                    + exitCode
                    );
                }

                if (!segment.exists()) {

                    throw new RuntimeException(
                            "Segment was not created: "
                                    + segment.getAbsolutePath()
                    );
                }

                segments.add(segment);

                System.out.println(
                        "Segment "
                                + i
                                + " created successfully."
                );
            }

            /*
             * ----------------------------------------------------
             * STEP 2: CREATE CONCAT FILE
             * ----------------------------------------------------
             */

            File concatFile =
                    new File(
                            outputDirectory,
                            "concat.txt"
                    );

            List<String> concatLines =
                    new ArrayList<>();

            for (File segment : segments) {

                String path =
                        segment.getAbsolutePath()
                                .replace("\\", "/");

                concatLines.add(
                        "file '" + path + "'"
                );
            }

            Files.write(
                    concatFile.toPath(),
                    concatLines
            );

            System.out.println(
                    "Concat file created: "
                            + concatFile.getAbsolutePath()
            );

            /*
             * ----------------------------------------------------
             * STEP 3: CREATE FINAL MP4
             * ----------------------------------------------------
             */

            File outputVideo =
                    new File(
                            outputDirectory,
                            "EduSync-Lecture.mp4"
                    );

            System.out.println(
                    "Creating final EduSync lecture..."
            );

            List<String> finalCommand =
                    new ArrayList<>();

            finalCommand.add(FFMPEG);

            finalCommand.add("-f");
            finalCommand.add("concat");

            finalCommand.add("-safe");
            finalCommand.add("0");

            finalCommand.add("-i");
            finalCommand.add(
                    concatFile.getAbsolutePath()
            );

            finalCommand.add("-c");
            finalCommand.add("copy");

            finalCommand.add("-y");

            finalCommand.add(
                    outputVideo.getAbsolutePath()
            );

            ProcessBuilder finalProcessBuilder =
                    new ProcessBuilder(finalCommand);

            finalProcessBuilder.inheritIO();

            Process finalProcess =
                    finalProcessBuilder.start();

            int finalExitCode =
                    finalProcess.waitFor();

            if (finalExitCode != 0) {

                throw new RuntimeException(
                        "FFmpeg failed while creating final video. "
                                + "Exit code: "
                                + finalExitCode
                );
            }

            if (!outputVideo.exists()) {

                throw new RuntimeException(
                        "Final MP4 was not created."
                );
            }

            System.out.println(
                    "================================"
            );

            System.out.println(
                    "VIDEO CREATED SUCCESSFULLY"
            );

            System.out.println(
                    outputVideo.getAbsolutePath()
            );

            System.out.println(
                    "================================"
            );

            return outputVideo.getAbsolutePath();

        } catch (Exception e) {

            e.printStackTrace();

            throw new RuntimeException(
                    "Unable to create video.",
                    e
            );
        }
    }
}