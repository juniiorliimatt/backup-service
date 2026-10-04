package br.com.backup.exceptions;

public class InvalidBackupRequestException extends RuntimeException {
    public InvalidBackupRequestException(final String message) {
        super(message);
    }
}
