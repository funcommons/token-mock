package fun.commons.tokenmock.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Profile("mock")
@RestControllerAdvice
public class MockExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MockExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleBad(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody("bad_request", ex.getMessage(), 400));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody("server_error", ex.getMessage(), 500));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> handleRt(RuntimeException ex) {
        log.error("mock unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("internal_error", "internal error: " + ex.getMessage(), 500));
    }

    public static Map<String, Object> errorBody(String type, String message, int code) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        err.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        return body;
    }
}
