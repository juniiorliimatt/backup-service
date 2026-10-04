package br.com.backup.exceptions;

/** Já existe um backup sendo gerado — só um por vez (o dump pesa no banco e no disco). */
public class BackupInProgressException extends RuntimeException {
    public BackupInProgressException() {
        super("Já existe um backup em andamento. Aguarde terminar e tente de novo.");
    }
}
