package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class MediaPipelineTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test
    void resolvesBundledToolsFromTheProjectWhenStartedByTheIde() {
        Path expected = Path.of("tools", "piper", "piper.exe").toAbsolutePath().normalize();
        assertEquals(expected, Path.of(MediaPipeline.resolveExecutable("./tools/piper/piper.exe")));
    }

    @Test
    void rejectsUnsafeSourcesAndPreservesSpeechInsteadOfClippingIt() throws Exception {
        assertEquals("https://www.tiktok.com/@owner/video/123", MediaPipeline.validateTikTokUrl(
                "https://www.tiktok.com/@owner/video/123?share_app_id=123"));
        assertEquals("https://vt.tiktok.com/Ab123/", MediaPipeline.validateTikTokUrl("https://vt.tiktok.com/Ab123/"));
        for (String unsafe : new String[] {"http://www.tiktok.com/@owner/video/123", "https://tiktok.com.evil.test/@x/video/1",
                "https://www.tiktok.com@127.0.0.1/@owner/video/123", "https://www.tiktok.com:8443/@owner/video/123",
                "https://www.tiktok.com/@owner", "https://www.tiktok.com/%2e%2e/video/123"}) {
            assertThrows(IllegalArgumentException.class, () -> MediaPipeline.validateTikTokUrl(unsafe));
        }
        var json = JsonMapper.builder().build();
        var segments = MediaPipeline.parseSegments(json.readTree("""
                {"segments":[{"start":0.5,"end":2.0,"text":"Hello"}, {"start":3,"end":4,"text":"World"}]}
                """), 5, 60);
        assertEquals(2, segments.size());
        assertEquals(0.5, segments.getFirst().start());
        var localSegments = MediaPipeline.parseSegments(json.readTree("""
                {"transcription":[{"offsets":{"from":250,"to":1250},"text":"Hello"}]}
                """), 2, 60);
        assertEquals(0.25, localSegments.getFirst().start());
        assertEquals(1.25, localSegments.getFirst().end());
        var merged = MediaPipeline.parseSegments(json.readTree("""
                {"segments":[{"start":0,"end":1,"text":"This is"},{"start":1.1,"end":2,"text":"one sentence."}]}
                """), 3, 60);
        assertEquals(1, merged.size());
        assertEquals("This is one sentence.", merged.getFirst().text());
        assertEquals(List.of("Một câu phụ đề ngắn được chia để không che", "quá nhiều hình ảnh"),
                MediaPipeline.captionChunks("Một câu phụ đề ngắn được chia để không che quá nhiều hình ảnh"));
        assertEquals("Cách làm món ăn nhanh. #longtieng #tiengviet #AI",
                MediaPipeline.postCaption(List.of("Cách làm món ăn", "nhanh")));
        assertThrows(IllegalArgumentException.class, () -> MediaPipeline.postCaption(List.of()));
        assertTrue(MediaPipeline.postCaption(List.of("nội dung ".repeat(30))).length() <= 180);
        assertEquals(1, MediaPipeline.speechSpeed(1.2, 2, 1.35));
        assertEquals(1.23, MediaPipeline.speechSpeed(2.4, 2, 1.35), 0.00001);
        assertEquals(2.11, MediaPipeline.speechSpeed(4.16, 2, 2.5), 0.00001);
        assertThrows(IOException.class, () -> MediaPipeline.speechSpeed(4, 2, 1.35));
        assertThrows(IOException.class, () -> MediaPipeline.parseSegments(json.readTree("""
                {"segments":[{"start":0,"end":3,"text":"One"}, {"start":1,"end":4,"text":"Two"}]}
                """), 5, 60));
        assertThrows(IOException.class, () -> MediaPipeline.parseSegments(json.readTree("{\"segments\":[]}"), 5, 60));
        var invalidKey = new MediaPipeline(new MockEnvironment().withProperty("app.ai-provider", "openai")
                .withProperty("app.openai-api-key", "\u0016"), json);
        IOException keyError = assertThrows(IOException.class,
                () -> invalidKey.process(directory.resolve("missing.mp4"), directory.resolve("invalid-key"), 0, ignored -> { }));
        assertTrue(keyError.getMessage().contains("OPENAI_API_KEY không hợp lệ"));
        assertEquals("Số dư API đã hết (HTTP 429, credit_balance_exhausted) tại audio/transcriptions. "
                        + "Kiểm tra Billing và Limits trên OpenAI; ứng dụng không tự thử lại.",
                MediaPipeline.aiErrorMessage(429, "audio/transcriptions", "credit_balance_exhausted"));
        assertTrue(MediaPipeline.aiErrorMessage(429, "audio/transcriptions", "insufficient_quota")
                .startsWith("Dự án không còn hạn mức hoặc số dư API"));
    }

    @Test
    void createsTheTikTokCaptionWithOllama() throws Exception {
        var json = JsonMapper.builder().build();
        HttpServer ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/tags", exchange -> {
            byte[] response = "{\"models\":[{\"name\":\"qwen3:1.7b\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ollama.createContext("/api/chat", exchange -> {
            byte[] response = """
                    {"message":{"content":"{\\"caption\\":\\"Mẹo nấu mì nhanh cho ngày bận rộn. #monngon #meobep\\"}"}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ollama.start();
        try {
            var environment = new MockEnvironment().withProperty("app.ai-provider", "local")
                    .withProperty("app.ollama-url", "http://127.0.0.1:" + ollama.getAddress().getPort() + "/api/chat");
            assertTrue(new MediaPipeline(environment, json).ollamaReady());
            assertEquals("Mẹo nấu mì nhanh cho ngày bận rộn. #monngon #meobep",
                    new MediaPipeline(environment, json).generatePostCaption(List.of("Cách nấu mì thật nhanh")));
        } finally {
            ollama.stop(0);
        }
    }

    @Test
    void rendersTimedDubUsingRealFfmpegAndLocalFakeAi() throws Exception {
        Path ffmpeg = Path.of("tools", "ffmpeg.exe").toAbsolutePath();
        Path ffprobe = Path.of("tools", "ffprobe.exe").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(ffmpeg) && Files.isRegularFile(ffprobe), "Run setup-tools.ps1 for the FFmpeg integration check");
        Path source = directory.resolve("source.mp4");
        command(ffmpeg.toString(), "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=24:d=4",
                "-f", "lavfi", "-i", "sine=frequency=400:duration=4", "-c:v", "libx264", "-c:a", "aac", "-shortest", source.toString());
        byte[] samples = new byte[16000]; // Half a second at 16 kHz, mono, signed 16-bit.
        ByteBuffer pcm = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8000; i++) pcm.putShort((short) (8000 * Math.sin(2 * Math.PI * 1000 * i / 16000)));
        ByteArrayOutputStream wave = new ByteArrayOutputStream();
        try (var audio = new AudioInputStream(new ByteArrayInputStream(samples), new AudioFormat(16000, 16, 1, true, false), 8000)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, wave);
        }
        var json = JsonMapper.builder().build();
        var requests = new AtomicInteger();
        HttpServer fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAi.createContext("/v1/", exchange -> {
            try {
                requests.incrementAndGet();
                assertEquals("Bearer local-test-key-1234567890", exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                byte[] response;
                switch (exchange.getRequestURI().getPath()) {
                    case "/v1/audio/transcriptions" -> {
                        assertTrue(body.contains("whisper-1") && body.contains("timestamp_granularities[]"));
                        response = """
                                {"segments":[{"start":0.25,"end":1.25,"text":"Hello"},{"start":2,"end":3.2,"text":"World"}]}
                                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    case "/v1/responses" -> {
                        assertEquals("json_schema", json.readTree(body).path("text").path("format").path("type").asText());
                        String translation = "{\"translations\":[{\"id\":0,\"text\":\"Xin chào\"},{\"id\":1,\"text\":\"Thế giới\"}]}";
                        response = json.writeValueAsBytes(Map.of("status", "completed", "output", List.of(Map.of("content",
                                List.of(Map.of("type", "output_text", "text", translation))))));
                    }
                    case "/v1/audio/speech" -> {
                        assertEquals("wav", json.readTree(body).path("response_format").asText());
                        response = wave.toByteArray();
                    }
                    default -> throw new IllegalArgumentException("Unexpected endpoint");
                }
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                byte[] message = failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, message.length);
                exchange.getResponseBody().write(message);
            } finally {
                exchange.close();
            }
        });
        fakeAi.start();
        try {
            var environment = new MockEnvironment().withProperty("app.ffmpeg", ffmpeg.toString())
                    .withProperty("app.ai-provider", "openai")
                    .withProperty("app.ffprobe", ffprobe.toString()).withProperty("app.openai-api-key", "local-test-key-1234567890")
                    .withProperty("app.openai-base-url", "http://127.0.0.1:" + fakeAi.getAddress().getPort() + "/v1");
            List<String> stages = new ArrayList<>();
            Path result = new MediaPipeline(environment, json).process(source, directory.resolve("work"), 0, stages::add);
            assertTrue(Files.size(result) > 1000);
            assertTrue(Files.readString(directory.resolve("work/post-caption.txt")).contains("Xin chào"));
            assertEquals(4, requests.get());
            assertEquals(List.of("EXTRACTING", "TRANSCRIBING", "TRANSLATING", "SPEAKING", "RENDERING"), stages);
            Path decoded = directory.resolve("result.pcm");
            command(ffmpeg.toString(), "-nostdin", "-y", "-v", "error", "-i", result.toString(), "-vn", "-ar", "8000",
                    "-ac", "1", "-f", "s16le", decoded.toString());
            ByteBuffer audio = ByteBuffer.wrap(Files.readAllBytes(decoded)).order(ByteOrder.LITTLE_ENDIAN);
            assertTrue(audio.limit() >= 60000, "Expected four seconds of decoded audio; inspect " + directory);
            assertTrue(meanAmplitude(audio, 0.04, 0.1) < 10, "Original soundtrack must be removed before the first spoken segment");
            assertTrue(meanAmplitude(audio, 0.35, 0.1) > 1000, "First translated segment must start at its timestamp");
            assertTrue(meanAmplitude(audio, 1.5, 0.1) < 10, "Gap between translated segments must remain silent");
            assertTrue(meanAmplitude(audio, 2.1, 0.1) > 1000, "Second translated segment must start at its timestamp");
        } finally {
            fakeAi.stop(0);
        }
    }

    private void command(String... args) throws Exception {
        Path log = directory.resolve("ffmpeg-test.log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "FFmpeg test timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    private double meanAmplitude(ByteBuffer audio, double start, double seconds) {
        int first = (int) (start * 8000), count = (int) (seconds * 8000);
        double total = 0;
        for (int i = first; i < first + count; i++) total += Math.abs((int) audio.getShort(i * 2));
        return total / count;
    }
}
