package com.example.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error",
                ex.getReason() == null ? "Không thực hiện được yêu cầu." : ex.getReason()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> invalid(Exception ex) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Dữ liệu yêu cầu không hợp lệ.";
        return ResponseEntity.badRequest().body(Map.of("error", message == null ? "Dữ liệu không hợp lệ." : message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("error", "Video vượt quá giới hạn 100 MB."));
    }
}
