package br.com.backup.exceptions;

/** Falha do pg_dump/openssl. A mensagem é genérica de propósito: a saída do binário só vai pro log do serviço. */
public class BackupFailedException extends RuntimeException {
    public BackupFailedException(final String message) {
        super(message);
    }
}
