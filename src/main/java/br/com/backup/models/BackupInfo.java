package br.com.backup.models;

import java.time.Instant;

/**
 * Um backup no disco. {@code path} é o caminho no HOST (config {@code backup.host-dir}), pra achar o arquivo;
 * {@code createdBy} é nulo em arquivos que apareceram na pasta sem passar pelo serviço (sem metadados).
 */
public record BackupInfo(String id,
                         String fileName,
                         String path,
                         long sizeBytes,
                         String sha256,
                         Instant createdAt,
                         String createdBy,
                         boolean encrypted) {
}
