package com.example.demo;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.springframework.http.HttpStatus.*;

@Service
public class JobService {
    public record Job(UUID id, String sourceUrl, String status, String error,
                      String createdAt, String updatedAt, int voiceId) { }

    private final JdbcTemplate db;
    private final MediaPipeline media;
    private final Path jobsDir;
    private final long maxBytes;
    // ponytail: one worker on one Windows machine; use a durable external queue only for multiple servers.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), Thread.ofPlatform().name("video-worker").factory());

    public JobService(JdbcTemplate db, MediaPipeline media, Environment env) throws IOException {
        this.db = db;
        this.media = media;
        jobsDir = Path.of(env.getProperty("app.data-dir", "./data")).toAbsolutePath().normalize().resolve("jobs");
        maxBytes = env.getProperty("app.max-input-bytes", Long.class, 104857600L);
        Files.createDirectories(jobsDir);
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        List<String> interrupted = db.queryForList("SELECT id FROM jobs WHERE status NOT IN ('COMPLETED','FAILED')", String.class);
        db.update("UPDATE jobs SET status='FAILED', error_message=?, updated_at=? "
                        + "WHERE status NOT IN ('COMPLETED','FAILED')",
                "Ứng dụng đã dừng khi đang xử lý. Bấm thử lại để tiếp tục bằng một lượt xử lý mới.", now());
        for (String id : interrupted) cleanup(directory(UUID.fromString(id)).resolve("work"));
    }

    public List<Job> list() {
        return db.query("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 50", (rs, row) -> new Job(
                UUID.fromString(rs.getString("id")), rs.getString("source_url"), rs.getString("status"),
                rs.getString("error_message"), rs.getString("created_at"), rs.getString("updated_at"),
                rs.getInt("voice_id")));
    }

    public Job get(UUID id) {
        return db.query("SELECT * FROM jobs WHERE id=?", (rs, row) -> new Job(id, rs.getString("source_url"),
                rs.getString("status"), rs.getString("error_message"), rs.getString("created_at"),
                rs.getString("updated_at"), rs.getInt("voice_id")), id.toString()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy tác vụ."));
    }

    public synchronized Job create(String url, MultipartFile upload, int voiceId) throws IOException {
        if (voiceId < 0 || voiceId > 64)
            throw new ResponseStatusException(BAD_REQUEST, "Giọng đọc phải nằm trong khoảng 1–65.");
        if (upload == null) url = MediaPipeline.validateTikTokUrl(url);
        else if (upload.isEmpty() || upload.getSize() > maxBytes)
            throw new ResponseStatusException(BAD_REQUEST, "Chọn video MP4 không rỗng, tối đa 100 MB.");
        if (worker.getQueue().remainingCapacity() == 0)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Hàng đợi đã đầy. Vui lòng thử lại sau.");
        UUID id = UUID.randomUUID();
        Path directory = directory(id);
        Files.createDirectory(directory);
        try {
            if (upload != null) upload.transferTo(directory.resolve("input.mp4"));
            String timestamp = now();
            db.update("INSERT INTO jobs(id,source_url,voice_id,status,created_at,updated_at) VALUES(?,?,?,'RECEIVED',?,?)",
                    id.toString(), url, voiceId, timestamp, timestamp);
        } catch (IOException | RuntimeException ex) {
            cleanup(directory);
            throw ex;
        }
        enqueue(id);
        return get(id);
    }

    public synchronized Job retry(UUID id) {
        Job job = get(id);
        if (!job.status().equals("FAILED"))
            throw new ResponseStatusException(CONFLICT, "Chỉ có thể thử lại tác vụ đã thất bại.");
        if (job.sourceUrl() == null && !Files.isRegularFile(directory(id).resolve("input.mp4")))
            throw new ResponseStatusException(CONFLICT, "File nguồn không còn. Hãy tải lên lại video.");
        if (worker.getQueue().remainingCapacity() == 0)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Hàng đợi đã đầy.");
        update(id, "RECEIVED", null);
        enqueue(id);
        return get(id);
    }

    public Path result(UUID id) {
        Job job = get(id);
        Path file = directory(id).resolve("result.mp4");
        if (!job.status().equals("COMPLETED") || !Files.isRegularFile(file))
            throw new ResponseStatusException(CONFLICT, "Video chưa sẵn sàng.");
        return file;
    }

    public String caption(UUID id) {
        result(id);
        Path file = directory(id).resolve("post-caption.txt");
        try {
            if (Files.isRegularFile(file)) {
                String caption = Files.readString(file).strip();
                if (!caption.isEmpty() && caption.length() <= 300) return caption;
            }
        } catch (IOException ignored) { }
        return "";
    }

    private void enqueue(UUID id) {
        try {
            worker.execute(() -> process(id));
        } catch (RejectedExecutionException ex) {
            update(id, "FAILED", "Hàng đợi không nhận thêm tác vụ. Vui lòng thử lại.");
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Hàng đợi không nhận thêm tác vụ.");
        }
    }

    private void process(UUID id) {
        Path work = directory(id).resolve("work");
        String failure = null;
        try {
            Files.createDirectories(work);
            Job job = get(id);
            Path source = directory(id).resolve("input.mp4");
            if (job.sourceUrl() != null) {
                update(id, "DOWNLOADING", null);
                source = media.download(job.sourceUrl(), work);
            }
            Path rendered = media.process(source, work, job.voiceId(), stage -> update(id, stage, null));
            Path caption = work.resolve("post-caption.txt");
            if (Files.isRegularFile(caption)) {
                Files.move(caption, directory(id).resolve("post-caption.txt"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(rendered, directory(id).resolve("result.mp4"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failure = "Xử lý bị dừng. Bạn có thể thử lại.";
        } catch (Exception ex) {
            failure = ex.getMessage() == null ? "Không xử lý được video." : ex.getMessage();
            failure = failure.replaceAll("(?i)(Bearer\\s+|sk-)[a-zA-Z0-9._-]+", "[redacted]");
            failure = failure.substring(0, Math.min(1800, failure.length()));
        } finally {
            cleanup(work);
        }
        update(id, failure == null ? "COMPLETED" : "FAILED", failure);
        if (failure == null) cleanup(directory(id).resolve("input.mp4"));
    }

    private void update(UUID id, String status, String error) {
        db.update("UPDATE jobs SET status=?,error_message=?,updated_at=? WHERE id=?",
                status, error, now(), id.toString());
    }

    private Path directory(UUID id) { return jobsDir.resolve(id.toString()); }
    private static String now() { return Instant.now().toString(); }

    private void cleanup(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(jobsDir) || target.equals(jobsDir))
            throw new IllegalArgumentException("Refusing to delete outside the job directory.");
        if (!Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ex) {
            // Keep the result and surface cleanup failures in the server log without including file contents.
            System.getLogger(JobService.class.getName()).log(System.Logger.Level.WARNING,
                    "Temporary files could not be removed from " + target);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
