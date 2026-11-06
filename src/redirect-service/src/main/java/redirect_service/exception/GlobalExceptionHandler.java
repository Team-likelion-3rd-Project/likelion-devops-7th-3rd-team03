package redirect_service.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RedirectNotFoundException.class)
    public ResponseEntity<Void> handleRedirectNotFound(RedirectNotFoundException exception) {
        return ResponseEntity.notFound().build();
    }
}
