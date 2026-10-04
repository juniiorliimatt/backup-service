package br.com.backup.exceptions;

/** Backup inexistente — também o que responde a um id forjado (path traversal), sem revelar nada do disco. */
public class BackupNotFoundException extends RuntimeException {
    public BackupNotFoundException(final String id) {
        super("Backup não encontrado");
    }
}
