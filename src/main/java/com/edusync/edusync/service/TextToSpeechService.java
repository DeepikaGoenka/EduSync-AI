package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class TextToSpeechService {

    public String generateNarration(
            String text,
            int slideNumber) {

        try {

            File directory =
                    new File("generated-slides");

            if (!directory.exists()) {
                directory.mkdirs();
            }

            File audioFile =
                    new File(
                            directory,
                            "narration-" +
                            slideNumber +
                            ".wav");

            ProcessBuilder processBuilder =
                    new ProcessBuilder(
                            "python",
                            "tts.py",
                            text,
                            audioFile.getAbsolutePath()
                    );

            processBuilder.inheritIO();

            Process process =
                    processBuilder.start();

            int exitCode =
                    process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException(
                        "Python TTS process failed."
                );
            }

            if (!audioFile.exists()) {
                throw new RuntimeException(
                        "Narration file was not created."
                );
            }

            return audioFile.getAbsolutePath();

        } catch (Exception e) {

            e.printStackTrace();

            throw new RuntimeException(
                    "Unable to generate narration.",
                    e
            );
        }
    }
}