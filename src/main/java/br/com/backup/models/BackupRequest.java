package br.com.backup.models;

/**
 * Corpo de {@code POST /api/v1/backups}. Sem {@code passphrase} (ou em branco) o backup sai sem cifra. A senha só
 * existe em memória durante o pedido: nunca é gravada, devolvida nem logada — {@code toString} a mascara.
 */
public record BackupRequest(String passphrase, String passphraseConfirmation) {

    @Override
    public String toString() {
        return "BackupRequest[passphrase=" + (passphrase == null || passphrase.isBlank() ? "<vazia>" : "***") + "]";
    }
}
