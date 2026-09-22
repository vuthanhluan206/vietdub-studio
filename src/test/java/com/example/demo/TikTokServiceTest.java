package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TikTokServiceTest {
    @TempDir Path directory;

    @Test
    void oauthRequiresConfigurationAndSessionAndStoresOnlyEncryptedCredentials() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("app.data-dir", directory.toString());
        TikTokService service = new TikTokService(environment, JsonMapper.builder().build());
        assertEquals(false, service.status().get("configured"));
        assertThrows(ResponseStatusException.class, () -> service.connect(new MockHttpSession()));
        environment.withProperty("app.tiktok-client-key", "test-client")
                .withProperty("app.tiktok-client-secret", "test-secret")
                .withProperty("app.tiktok-redirect-uri", "http://127.0.0.1:8080/api/tiktok/callback")
                .withProperty("app.token-encryption-key", Base64.getEncoder().encodeToString(new byte[32]));

        MockHttpSession session = new MockHttpSession();
        String url = service.connect(session);
        TikTokService.OAuthState state = (TikTokService.OAuthState) session.getAttribute(TikTokService.STATE_ATTRIBUTE);
        assertTrue(url.startsWith("https://www.tiktok.com/v2/auth/authorize/?"));
        assertTrue(url.contains("scope=user.info.basic%2Cvideo.publish"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("code_challenge="));
        assertTrue(state.codeVerifier().length() >= 43 && state.codeVerifier().length() <= 128);
        assertFalse(url.contains("test-secret"));
        assertThrows(ResponseStatusException.class, () -> service.consumeState(new MockHttpSession(), state.value()));
        assertThrows(ResponseStatusException.class, () -> service.consumeState(session, "wrong-state"));
        service.consumeState(session, state.value());
        assertThrows(ResponseStatusException.class, () -> service.consumeState(session, state.value()));
        session.setAttribute(TikTokService.STATE_ATTRIBUTE,
                new TikTokService.OAuthState(state.value(), state.codeVerifier(), Instant.EPOCH));
        assertThrows(ResponseStatusException.class, () -> service.consumeState(session, state.value()));

        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        TikTokService.Token token = new TikTokService.Token("access-secret", "refresh-secret", future, future,
                "user.info.basic,video.publish");
        service.saveToken(token);
        assertEquals(token, service.readToken());
        assertEquals(true, service.status().get("connected"));
        assertEquals(true, service.status().get("postingEnabled"));
        assertFalse(service.status().toString().contains("secret"));
        byte[] original = Files.readAllBytes(directory.resolve("tiktok-token.enc"));
        assertFalse(new String(original, StandardCharsets.ISO_8859_1).contains("access-secret"));
        service.saveToken(token);
        assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(directory.resolve("tiktok-token.enc"))));
        byte[] changedKey = new byte[32];
        changedKey[0] = 1;
        environment.withProperty("app.token-encryption-key", Base64.getEncoder().encodeToString(changedKey));
        assertThrows(ResponseStatusException.class, service::readToken);

        var creator = new TikTokService.CreatorInfo("owner", "Owner", java.util.List.of("SELF_ONLY"),
                true, false, false, 180);
        var valid = new TikTokService.PostOptions("Mô tả", "SELF_ONLY", false, false, false,
                false, false, false);
        assertDoesNotThrow(() -> TikTokService.validatePost(creator, valid, 30));
        var invalid = new TikTokService.PostOptions("Mô tả", "SELF_ONLY", true, false, false,
                false, false, false);
        assertThrows(ResponseStatusException.class, () -> TikTokService.validatePost(creator, invalid, 30));
    }
}
