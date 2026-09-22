package com.example.demo;

import jakarta.servlet.http.HttpSession;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TikTokService {
    static final String STATE_ATTRIBUTE = "tiktok.oauth.state";
    private static final String API = "https://open.tiktokapis.com";
    private static final long UPLOAD_CHUNK = 32L * 1024 * 1024;
    private static final Set<String> PRIVACY_LEVELS = Set.of(
            "PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY");

    private final Environment environment;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public TikTokService(Environment environment, ObjectMapper json) {
        this.environment = environment;
        this.json = json;
    }

    public String connect(HttpSession session) {
        requireConfiguration();
        String state = randomToken(32);
        String verifier = randomToken(64);
        session.setAttribute(STATE_ATTRIBUTE, new OAuthState(state, verifier, Instant.now().plusSeconds(600)));
        return "https://www.tiktok.com/v2/auth/authorize/?" + form(Map.of(
                "client_key", setting("tiktok-client-key"), "response_type", "code",
                "scope", "user.info.basic,video.publish", "redirect_uri", setting("tiktok-redirect-uri"),
                "state", state, "code_challenge", sha256Hex(verifier), "code_challenge_method", "S256",
                "disable_auto_auth", "1"));
    }

    public synchronized void complete(HttpSession session, String state, String code, String error) {
        OAuthState pending = consumeState(session, state);
        if (error != null || code == null || code.isBlank() || code.length() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TikTok chưa cấp quyền kết nối. Hãy thử lại.");
        }
        requireConfiguration();
        saveToken(exchange(Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", setting("tiktok-redirect-uri"), "code_verifier", pending.codeVerifier())));
    }

    OAuthState consumeState(HttpSession session, String state) {
        if (session == null || state == null || state.length() > 128) throw invalidState();
        synchronized (session) {
            Object value = session.getAttribute(STATE_ATTRIBUTE);
            if (!(value instanceof OAuthState pending) || !pending.expiresAt().isAfter(Instant.now())
                    || !MessageDigest.isEqual(pending.value().getBytes(StandardCharsets.UTF_8),
                    state.getBytes(StandardCharsets.UTF_8))) throw invalidState();
            session.removeAttribute(STATE_ATTRIBUTE);
            return pending;
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", false);
        result.put("connected", false);
        result.put("postingEnabled", false);
        try {
            requireConfiguration();
        } catch (ResponseStatusException ex) {
            result.put("configurationError", ex.getReason());
            return result;
        }
        result.put("configured", true);
        Token token = readToken();
        if (token == null || token.refreshExpiresAt() <= Instant.now().getEpochSecond()) return result;
        if (token.expiresAt() <= Instant.now().plusSeconds(60).getEpochSecond()) {
            token = exchange(Map.of("grant_type", "refresh_token", "refresh_token", token.refreshToken()));
            saveToken(token);
        }
        result.put("connected", true);
        result.put("postingEnabled", hasScope(token, "video.publish"));
        result.put("scopes", token.scope());
        result.put("expiresAt", Instant.ofEpochSecond(token.expiresAt()).toString());
        return result;
    }

    public CreatorInfo creatorInfo() {
        Token token = currentToken("video.publish");
        JsonNode data = postApi("/v2/post/publish/creator_info/query/", null, token).path("data");
        List<String> privacy = new ArrayList<>();
        data.path("privacy_level_options").forEach(value -> {
            String option = value.asString("");
            if (PRIVACY_LEVELS.contains(option)) privacy.add(option);
        });
        int maxDuration = data.path("max_video_post_duration_sec").asInt(0);
        String nickname = clean(data.path("creator_nickname").asString(""), 100);
        if (nickname.isBlank() || privacy.isEmpty() || maxDuration <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "TikTok trả thông tin tài khoản không đầy đủ. Hãy kết nối lại.");
        }
        return new CreatorInfo(clean(data.path("creator_username").asString(""), 100), nickname,
                List.copyOf(privacy), data.path("comment_disabled").asBoolean(true),
                data.path("duet_disabled").asBoolean(true), data.path("stitch_disabled").asBoolean(true), maxDuration);
    }

    public PublishResult publish(Path video, PostOptions options, double durationSeconds) throws IOException {
        Token token = currentToken("video.publish");
        CreatorInfo creator = creatorInfo();
        validatePost(creator, options, durationSeconds);
        if (!Files.isRegularFile(video)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video đăng TikTok phải là MP4 không rỗng, tối đa 100 MB.");
        }
        long size = Files.size(video);
        if (size <= 0 || size > 100L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video đăng TikTok phải là MP4 không rỗng, tối đa 100 MB.");
        }

        long chunkSize = size <= 64L * 1024 * 1024 ? size : UPLOAD_CHUNK;
        long totalChunks = Math.max(1, size / chunkSize);
        Map<String, Object> postInfo = new LinkedHashMap<>();
        postInfo.put("title", options.title().strip());
        postInfo.put("privacy_level", options.privacyLevel());
        postInfo.put("disable_comment", !options.allowComment());
        postInfo.put("disable_duet", !options.allowDuet());
        postInfo.put("disable_stitch", !options.allowStitch());
        postInfo.put("brand_organic_toggle", options.yourBrand());
        postInfo.put("brand_content_toggle", options.brandedContent());
        postInfo.put("is_aigc", true);
        JsonNode initialized = postApi("/v2/post/publish/video/init/", Map.of(
                "post_info", postInfo,
                "source_info", Map.of("source", "FILE_UPLOAD", "video_size", size,
                        "chunk_size", chunkSize, "total_chunk_count", totalChunks)), token).path("data");
        String publishId = clean(initialized.path("publish_id").asString(""), 64);
        URI uploadUrl = validUploadUrl(initialized.path("upload_url").asString(""));
        if (publishId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TikTok không trả mã đăng video.");
        upload(video, uploadUrl, size, chunkSize, totalChunks);
        return new PublishResult(publishId, "PROCESSING_UPLOAD");
    }

    public PublishStatus publishStatus(String publishId) {
        if (publishId == null || !publishId.matches("[A-Za-z0-9._~:-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã đăng TikTok không hợp lệ.");
        }
        JsonNode data = postApi("/v2/post/publish/status/fetch/", Map.of("publish_id", publishId),
                currentToken("video.publish")).path("data");
        List<String> postIds = new ArrayList<>();
        data.path("publicaly_available_post_id").forEach(value -> postIds.add(clean(value.asString(""), 32)));
        return new PublishStatus(clean(data.path("status").asString(""), 40),
                clean(data.path("fail_reason").asString(""), 160), data.path("uploaded_bytes").asLong(0), postIds);
    }

    static void validatePost(CreatorInfo creator, PostOptions options, double durationSeconds) {
        if (options == null || options.title() == null || options.title().length() > 2200)
            throw bad("Nội dung mô tả tối đa 2.200 ký tự.");
        if (options.privacyLevel() == null || !creator.privacyLevelOptions().contains(options.privacyLevel()))
            throw bad("Hãy chọn quyền riêng tư do TikTok cung cấp.");
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > creator.maxVideoPostDurationSec() + .05)
            throw bad("Video dài hơn thời lượng tài khoản TikTok cho phép.");
        if ((creator.commentDisabled() && options.allowComment()) || (creator.duetDisabled() && options.allowDuet())
                || (creator.stitchDisabled() && options.allowStitch()))
            throw bad("Một tùy chọn tương tác đã bị tắt trong cài đặt tài khoản TikTok.");
        if (!options.commercialContent() && (options.yourBrand() || options.brandedContent()))
            throw bad("Hãy bật khai báo nội dung thương mại trước khi chọn nhãn thương hiệu.");
        if (options.commercialContent() && !options.yourBrand() && !options.brandedContent())
            throw bad("Hãy chọn thương hiệu của bạn, nội dung có tài trợ hoặc cả hai.");
        if (options.brandedContent() && "SELF_ONLY".equals(options.privacyLevel()))
            throw bad("Nội dung có tài trợ không thể đặt quyền riêng tư Chỉ mình tôi.");
    }

    private synchronized Token currentToken(String requiredScope) {
        requireConfiguration();
        Token token = readToken();
        long now = Instant.now().getEpochSecond();
        if (token == null || token.refreshExpiresAt() <= now) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chưa kết nối TikTok hoặc phiên đã hết hạn.");
        }
        if (token.expiresAt() <= now + 60) {
            token = exchange(Map.of("grant_type", "refresh_token", "refresh_token", token.refreshToken()));
            saveToken(token);
        }
        if (!hasScope(token, requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "TikTok chưa cấp quyền video.publish. Hãy kết nối lại và đồng ý quyền đăng video.");
        }
        return token;
    }

    private JsonNode postApi(String path, Object body, Token token) {
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không tạo được yêu cầu TikTok.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + safeToken(token.accessToken()))
                .header("Content-Type", "application/json; charset=UTF-8").POST(publisher).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = json.readTree(response.body());
            String code = root.path("error").path("code").asString("");
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !"ok".equals(code)) {
                throw providerError(response.statusCode(), code, root.path("error").path("message").asString(""));
            }
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kết nối TikTok bị gián đoạn.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không đọc được phản hồi từ TikTok.");
        }
    }

    private void upload(Path video, URI uploadUrl, long size, long chunkSize, long totalChunks) throws IOException {
        try (InputStream input = Files.newInputStream(video)) {
            long offset = 0;
            for (long index = 0; index < totalChunks; index++) {
                long length = index == totalChunks - 1 ? size - offset : chunkSize;
                byte[] bytes = input.readNBytes(Math.toIntExact(length));
                if (bytes.length != length) throw new IOException("Không đọc đủ dữ liệu MP4 để đăng TikTok.");
                HttpRequest request = HttpRequest.newBuilder(uploadUrl).timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "video/mp4")
                        .header("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + size)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                int expected = index == totalChunks - 1 ? 201 : 206;
                if (response.statusCode() != expected) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "TikTok từ chối phần tải video lên (HTTP " + response.statusCode() + ").");
                }
                offset += length;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tải video lên TikTok bị gián đoạn.");
        }
    }

    private Token exchange(Map<String, String> values) {
        Map<String, String> body = new LinkedHashMap<>(values);
        body.put("client_key", setting("tiktok-client-key"));
        body.put("client_secret", setting("tiktok-client-secret"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/v2/oauth/token/"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(body))).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException();
            JsonNode value = json.readTree(response.body());
            if (value.has("error") || value.path("access_token").asString("").isBlank()
                    || value.path("refresh_token").asString("").isBlank()
                    || value.path("expires_in").asLong() <= 0 || value.path("refresh_expires_in").asLong() <= 0) {
                throw new IllegalStateException();
            }
            long now = Instant.now().getEpochSecond();
            return new Token(safeToken(value.path("access_token").asString()),
                    safeToken(value.path("refresh_token").asString()),
                    Math.addExact(now, value.path("expires_in").asLong()),
                    Math.addExact(now, value.path("refresh_expires_in").asLong()), value.path("scope").asString(""));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kết nối TikTok bị gián đoạn.");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "TikTok không cấp được token. Kiểm tra Client key, Client secret, Redirect URI và kết nối lại.");
        }
    }

    Token readToken() {
        Path path = tokenPath();
        if (!Files.exists(path)) return null;
        try {
            byte[] encrypted = Files.readAllBytes(path);
            if (encrypted.length < 28) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, encrypted, 0, 12));
            return json.readValue(cipher.doFinal(encrypted, 12, encrypted.length - 12), Token.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Không đọc được token TikTok đã mã hóa. Kiểm tra khóa mã hóa hoặc kết nối lại.");
        }
    }

    void saveToken(Token token) {
        Path temporary = null;
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(token));
            Path path = tokenPath();
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "tiktok-token-", ".tmp");
            Files.write(temporary, ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không lưu được token TikTok đã mã hóa.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private void requireConfiguration() {
        for (String name : new String[]{"tiktok-client-key", "tiktok-client-secret", "tiktok-redirect-uri", "token-encryption-key"}) {
            if (setting(name).isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình app." + name + ".");
        }
        try {
            URI redirect = URI.create(setting("tiktok-redirect-uri"));
            if (!Set.of("http", "https").contains(redirect.getScheme())
                    || !Set.of("localhost", "127.0.0.1").contains(redirect.getHost()) || redirect.getPort() <= 0
                    || redirect.getUserInfo() != null || redirect.getQuery() != null || redirect.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "app.tiktok-redirect-uri phải là URL localhost/127.0.0.1 có cổng, không có query hoặc fragment.");
        }
        encryptionKey();
    }

    private SecretKeySpec encryptionKey() {
        try {
            byte[] key = Base64.getDecoder().decode(setting("token-encryption-key"));
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "app.token-encryption-key phải là khóa ngẫu nhiên 32 byte dạng Base64.");
        }
    }

    private Path tokenPath() {
        return Path.of(environment.getProperty("app.data-dir", "./data")).toAbsolutePath().normalize()
                .resolve("tiktok-token.enc");
    }

    private String setting(String name) { return environment.getProperty("app." + name, "").trim(); }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean hasScope(Token token, String scope) {
        return List.of(token.scope().split("[,\\s]+")).contains(scope);
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static URI validUploadUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null
                    || !(host.equals("tiktokapis.com") || host.endsWith(".tiktokapis.com"))
                    || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TikTok trả địa chỉ tải video không hợp lệ.");
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank() || value.length() > 4096 || !value.matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException("Invalid credential");
        return value;
    }

    private static String clean(String value, int max) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").strip();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    static ResponseStatusException providerError(int status, String code, String message) {
        if ("unaudited_client_can_only_post_to_private_accounts".equals(code)) {
            return new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ứng dụng TikTok chưa qua kiểm duyệt. Hãy bật Tài khoản riêng tư trên TikTok "
                            + "và chọn quyền riêng tư Chỉ mình tôi (SELF_ONLY), rồi đăng lại.");
        }
        HttpStatus result = status == 429 || Set.of("rate_limit_exceeded", "spam_risk_too_many_posts",
                        "reached_active_user_cap").contains(code) ? HttpStatus.TOO_MANY_REQUESTS
                : status == 401 || "access_token_invalid".equals(code) ? HttpStatus.UNAUTHORIZED
                : status == 403 || "scope_not_authorized".equals(code) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_GATEWAY;
        String detail = clean(code, 80);
        String description = clean(message, 160);
        return new ResponseStatusException(result, "TikTok từ chối yêu cầu"
                + (detail.isBlank() ? "" : " (" + detail + ")")
                + (description.isBlank() ? "." : ": " + description));
    }

    private static ResponseStatusException invalidState() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiên kết nối TikTok không hợp lệ hoặc đã hết hạn.");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record OAuthState(String value, String codeVerifier, Instant expiresAt) { }

    record Token(String accessToken, String refreshToken, long expiresAt, long refreshExpiresAt, String scope) {
        @Override public String toString() { return "[TikTok credentials redacted]"; }
    }

    public record CreatorInfo(String username, String nickname, List<String> privacyLevelOptions,
                              boolean commentDisabled, boolean duetDisabled, boolean stitchDisabled,
                              int maxVideoPostDurationSec) { }

    public record PostOptions(String title, String privacyLevel, boolean allowComment, boolean allowDuet,
                              boolean allowStitch, boolean commercialContent, boolean yourBrand,
                              boolean brandedContent) { }

    public record PublishResult(String publishId, String status) { }

    public record PublishStatus(String status, String failReason, long uploadedBytes, List<String> postIds) { }
}
