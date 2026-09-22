package com.example.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api")
public class JobController {
    public record CreateJob(@NotBlank @Size(max = 2048) String url, boolean rightsConfirmed, int voiceId) { }
    private final JobService jobs;
    private final MediaPipeline media;

    public JobController(JobService jobs, MediaPipeline media) { this.jobs = jobs; this.media = media; }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "UP", "aiConfigured", media.aiConfigured(), "aiProvider", media.provider(),
                "postingEnabled", false, "voice", media.voiceLabel());
    }

    @GetMapping("/jobs")
    List<JobService.Job> list() { return jobs.list(); }

    @PostMapping("/voices/{voiceId}/preview")
    ResponseEntity<FileSystemResource> previewVoice(@PathVariable int voiceId) throws IOException, InterruptedException {
        if (voiceId < 0 || voiceId > 64) throw new ResponseStatusException(BAD_REQUEST, "Giọng đọc phải nằm trong khoảng 1–65.");
        var resource = new FileSystemResource(media.voicePreview(voiceId));
        return ResponseEntity.ok().header("Content-Type", "audio/wav")
                .header("Cache-Control", "private, max-age=86400")
                .contentLength(resource.contentLength()).body(resource);
    }

    @PostMapping("/jobs")
    ResponseEntity<JobService.Job> create(@Valid @RequestBody CreateJob body) throws IOException {
        requireRights(body.rightsConfirmed());
        return accepted(jobs.create(body.url(), null, body.voiceId()));
    }

    @PostMapping(value = "/jobs/upload", consumes = "multipart/form-data")
    ResponseEntity<JobService.Job> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "false") boolean rightsConfirmed,
                                         @RequestParam(defaultValue = "0") int voiceId) throws IOException {
        requireRights(rightsConfirmed);
        return accepted(jobs.create(null, file, voiceId));
    }

    @GetMapping("/jobs/{id}")
    JobService.Job get(@PathVariable UUID id) { return jobs.get(id); }

    @PostMapping("/jobs/{id}/retry")
    ResponseEntity<JobService.Job> retry(@PathVariable UUID id) { return accepted(jobs.retry(id)); }

    @GetMapping("/jobs/{id}/result")
    Map<String, Object> result(@PathVariable UUID id) {
        jobs.result(id);
        return Map.of("jobId", id, "downloadUrl", "/api/jobs/" + id + "/file", "postedToTikTok", false);
    }

    @GetMapping("/jobs/{id}/file")
    ResponseEntity<FileSystemResource> file(@PathVariable UUID id) throws IOException {
        var resource = new FileSystemResource(jobs.result(id));
        return ResponseEntity.ok().header("Content-Type", "video/mp4")
                .header("Content-Disposition", "attachment; filename=\"dubbed-" + id + ".mp4\"")
                .contentLength(resource.contentLength()).body(resource);
    }

    private static ResponseEntity<JobService.Job> accepted(JobService.Job job) {
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }

    private static void requireRights(boolean confirmed) {
        if (!confirmed) throw new ResponseStatusException(BAD_REQUEST, "Hãy xác nhận bạn có quyền sử dụng video.");
    }
}
