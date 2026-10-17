package com.local.nativespace;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class PythonExecutor {

    private final ExecutorService executor =
            Executors.newFixedThreadPool(3);

      public CompletableFuture<String> processPdf(String pdfPath) {

        return CompletableFuture.supplyAsync(() -> {

            try {
                ProcessBuilder builder = new ProcessBuilder(
                        "python",
                        "C:\\Harsh Projects\\springAI\\src\\main\\python\\pdf_chunker.py",
                        pdfPath
                );

                // Merge stderr into stdout to avoid blocked error pipe.
                builder.redirectErrorStream(true);

                Process process = builder.start();

                // Read concurrently so Python cannot block on a full pipe.
                CompletableFuture<String> outputFuture =
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                return new String(
                                        process.getInputStream().readAllBytes(),
                                        StandardCharsets.UTF_8
                                );
                            } catch (IOException e) {
                                throw new CompletionException(e);
                            }
                        });

                boolean finished = process.waitFor(
                        5, TimeUnit.MINUTES
                );

                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new RuntimeException(
                            "Docling processing timed out"
                    );
                }

                String output = outputFuture.join();

                if (process.exitValue() != 0) {
                    throw new RuntimeException(
                            "Python failed: " + output
                    );
                }

                return output;

            } catch (Exception e) {
                throw new CompletionException(e);
            }

        }, executor);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
