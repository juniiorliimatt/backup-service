package br.com.backup.exceptions.handler;

import br.com.backup.exceptions.BackupFailedException;
import br.com.backup.exceptions.BackupInProgressException;
import br.com.backup.exceptions.BackupNotFoundException;
import br.com.backup.exceptions.InvalidBackupRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Erros no formato Problem Details (RFC 9457). Estende {@link ResponseEntityExceptionHandler} pra que exceções do
 * próprio MVC mantenham o status correto; o catch-all nunca devolve stack trace nem detalhe interno.
 */
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(BackupNotFoundException.class)
    public ProblemDetail handleNotFound(final BackupNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidBackupRequestException.class)
    public ProblemDetail handleInvalid(final InvalidBackupRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BackupInProgressException.class)
    public ProblemDetail handleInProgress(final BackupInProgressException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(BackupFailedException.class)
    public ProblemDetail handleFailed(final BackupFailedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception exception) {
        logger.error("Erro inesperado", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor");
    }
}
