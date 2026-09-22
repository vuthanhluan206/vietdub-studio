package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class DemoApplicationTests {
    private static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory(Path.of("target"), "jobs-test-"); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", DATA::toString);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:job-test;DB_CLOSE_DELAY=-1");
        registry.add("app.openai-api-key", () -> "");
        registry.add("app.tiktok-client-key", () -> "");
    }

    @Autowired WebApplicationContext context;
    @Autowired SecurityHeadersFilter securityHeadersFilter;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean MediaPipeline media;

    @Test
    void localJobsPersistFailRetryAndServeOnlyFinishedResults() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityHeadersFilter).build();
        mvc.perform(get("/api/jobs")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/jobs").contentType("application/json")
                .content("{\"url\":\"https://127.0.0.1/video/1\",\"rightsConfirmed\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/jobs").contentType("application/json")
                .content("{\"url\":\"https://www.tiktok.com/@demo/video/123\"}"))
                .andExpect(status().isBadRequest());

        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Consumer<String> stage = invocation.getArgument(3);
            stage.accept("TRANSCRIBING");
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
            throw new IOException("AI temporarily unavailable");
        }).when(media).process(any(), any(), anyInt(), any());
        MockMultipartFile upload = new MockMultipartFile("file", "input.mp4", "video/mp4", new byte[]{1, 2, 3});
        String body = mvc.perform(multipart("/api/jobs/upload").file(upload).param("rightsConfirmed", "true").param("voiceId", "4"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("id").asText());
        assertEquals(4, jobs.get(id).voiceId());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            mvc.perform(get("/api/jobs/" + id + "/result")).andExpect(status().isConflict());
            mvc.perform(post("/api/jobs/" + id + "/retry")).andExpect(status().isConflict());
        } finally { release.countDown(); }
        awaitStatus(id, "FAILED");
        assertEquals("AI temporarily unavailable", jobs.get(id).error());
        assertTrue(Files.isRegularFile(DATA.resolve("jobs/" + id + "/input.mp4")));
        assertFalse(Files.exists(DATA.resolve("jobs/" + id + "/work")));

        doAnswer(invocation -> {
            Path work = invocation.getArgument(1);
            return Files.write(work.resolve("rendered.mp4"), new byte[]{4, 5, 6});
        }).when(media).process(any(), any(), anyInt(), any());
        mvc.perform(post("/api/jobs/" + id + "/retry")).andExpect(status().isAccepted());
        awaitStatus(id, "COMPLETED");
        assertNull(jobs.get(id).error());
        mvc.perform(get("/api/jobs/" + id + "/file"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{4, 5, 6}));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (Files.exists(DATA.resolve("jobs/" + id + "/input.mp4"))) Thread.sleep(20);
        });
        assertEquals("COMPLETED", db.queryForObject("SELECT status FROM jobs WHERE id=?", String.class, id.toString()));

        UUID interrupted = UUID.randomUUID();
        db.update("INSERT INTO jobs(id,status,created_at,updated_at) VALUES(?,'RENDERING','now','now')", interrupted.toString());
        jobs.recoverInterruptedJobs();
        assertEquals("FAILED", jobs.get(interrupted).status());
        assertEquals("COMPLETED", jobs.get(id).status());
    }

    @Test
    void voicePreviewServesWavAndRejectsInvalidVoice() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityHeadersFilter).build();
        byte[] wav = new byte[]{82, 73, 70, 70, 1, 2, 3};
        Path file = Files.write(DATA.resolve("voice-preview.wav"), wav);
        doReturn(file).when(media).voicePreview(4);

        mvc.perform(post("/api/voices/4/preview"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "audio/wav"))
                .andExpect(content().bytes(wav));
        mvc.perform(post("/api/voices/65/preview")).andExpect(status().isBadRequest());
    }

    private void awaitStatus(UUID id, String status) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!jobs.get(id).status().equals(status)) Thread.sleep(20);
        });
    }
}
