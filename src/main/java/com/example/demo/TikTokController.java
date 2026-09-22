package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tiktok")
public class TikTokController {
    private final TikTokService tiktok;
    private final JobService jobs;
    private final MediaPipeline media;

    public TikTokController(TikTokService tiktok, JobService jobs, MediaPipeline media) {
        this.tiktok = tiktok;
        this.jobs = jobs;
        this.media = media;
    }

    @PostMapping("/connect")
    public ResponseEntity<Map<String, String>> connect(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("authorizationUrl", tiktok.connect(session)));
    }

    @GetMapping(value = "/callback", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> callback(HttpServletRequest request,
            @RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error) {
        tiktok.complete(request.getSession(false), state, code, error);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body("Đã kết nối TikTok và cấp quyền đăng video. Bạn có thể đóng tab này rồi quay lại công cụ.");
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tiktok.status());
    }

    @GetMapping("/creator")
    public ResponseEntity<TikTokService.CreatorInfo> creator() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tiktok.creatorInfo());
    }

    @PostMapping("/publish/{jobId}")
    public ResponseEntity<TikTokService.PublishResult> publish(@PathVariable UUID jobId,
            @RequestBody PublishRequest request) throws IOException, InterruptedException {
        if (request == null || !request.consent()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Hãy xác nhận điều khoản sử dụng nhạc trước khi đăng.");
        }
        var video = jobs.result(jobId);
        var options = new TikTokService.PostOptions(request.title(), request.privacyLevel(),
                request.allowComment(), request.allowDuet(), request.allowStitch(),
                request.commercialContent(), request.yourBrand(), request.brandedContent());
        return ResponseEntity.accepted().body(tiktok.publish(video, options, media.duration(video)));
    }

    @GetMapping("/publish/{publishId}/status")
    public ResponseEntity<TikTokService.PublishStatus> publishStatus(@PathVariable String publishId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tiktok.publishStatus(publishId));
    }

    public record PublishRequest(String title, String privacyLevel, boolean allowComment, boolean allowDuet,
                                 boolean allowStitch, boolean commercialContent, boolean yourBrand,
                                 boolean brandedContent, boolean consent) { }
}
