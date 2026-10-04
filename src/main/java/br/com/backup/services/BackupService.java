package br.com.backup.services;

import br.com.backup.config.BackupProperties;
import br.com.backup.exceptions.BackupFailedException;
import br.com.backup.exceptions.BackupInProgressException;
import br.com.backup.exceptions.BackupNotFoundException;
import br.com.backup.exceptions.InvalidBackupRequestException;
import br.com.backup.models.BackupInfo;
import br.com.backup.models.BackupRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gera, lista, entrega e apaga backups do Postgres ({@code pg_dump --format=custom}, banco inteiro, todos os schemas)
 * numa pasta. Cada backup é um arquivo {@code <banco>_<yyyyMMdd_HHmmss>.dump} (ou {@code .dump.enc} se cifrado com
 * openssl AES-256-CBC + PBKDF2) mais um {@code .meta.json} ao lado (quem pediu, sha256, cifrado). Não há tabela: a pasta
 * é a fonte da verdade, então o serviço não precisa de schema nem de privilégio de escrita no banco.
 *
 * <p>Um backup por vez ({@link ReentrantLock}). Restore NÃO existe aqui de propósito — só por script.
 */
@Service
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final String DUMP = ".dump";
    private static final String ENCRYPTED = ".dump.enc";
    private static final String META = ".meta.json";
    private static final int PBKDF2_ITERATIONS = 600_000;

    private final BackupProperties properties;
    private final CommandRunner runner;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String inProgressId;

    public BackupService(final BackupProperties properties, final CommandRunner runner, final Clock clock) {
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
    }

    /** Arquivo de um backup pra download — o caminho é sempre derivado do id validado, nunca de texto do cliente. */
    public record BackupFile(Path path, String fileName) {
    }

    public BackupInfo gerar(final String requestedBy, final BackupRequest request) {
        final String passphrase = validatedPassphrase(request);
        if (!lock.tryLock()) {
            throw new BackupInProgressException();
        }
        final Instant now = clock.instant();
        final String id = properties.pg().database() + "_" + STAMP.format(now);
        inProgressId = id;
        final Path plain = properties.dir().resolve(id + DUMP);
        final Path encrypted = properties.dir().resolve(id + ENCRYPTED);
        final Path meta = properties.dir().resolve(id + META);
        try {
            Files.createDirectories(properties.dir());
            dump(plain);
            Path finalFile = plain;
            if (passphrase != null) {
                encrypt(plain, encrypted, passphrase);
                Files.deleteIfExists(plain);
                finalFile = encrypted;
            }
            final String sha256 = sha256(finalFile);
            writeMeta(meta, now, requestedBy, passphrase != null, sha256);
            logger.info("Backup {} gerado por {} (cifrado={}, {} bytes)", id, requestedBy, passphrase != null, Files.size(finalFile));
            return toInfo(id, finalFile, now, requestedBy, passphrase != null, sha256);
        } catch (IOException | RuntimeException e) {
            cleanUp(plain, encrypted, meta);
            if (e instanceof BackupFailedException failed) {
                throw failed;
            }
            logger.error("Falha ao gerar o backup {}", id, e);
            throw new BackupFailedException("Não foi possível gerar o backup. Veja os logs do serviço.");
        } finally {
            inProgressId = null;
            lock.unlock();
        }
    }

    /** Backups da pasta, do mais novo pro mais antigo. Arquivos que não seguem o padrão são ignorados. */
    public List<BackupInfo> listar() {
        if (!Files.isDirectory(properties.dir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(properties.dir())) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> idOf(p.getFileName().toString()) != null)
                    .filter(p -> !idOf(p.getFileName().toString()).equals(inProgressId))
                    .map(this::describe)
                    .sorted(Comparator.comparing(BackupInfo::createdAt).thenComparing(BackupInfo::id).reversed())
                    .toList();
        } catch (IOException e) {
            logger.error("Falha ao listar {}", properties.dir(), e);
            throw new BackupFailedException("Não foi possível listar os backups. Veja os logs do serviço.");
        }
    }

    public BackupFile arquivo(final String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new BackupNotFoundException(id);
        }
        for (final String suffix : List.of(ENCRYPTED, DUMP)) {
            final Path candidate = properties.dir().resolve(id + suffix).normalize();
            if (candidate.getParent() != null && candidate.getParent().equals(properties.dir().normalize())
                    && Files.isRegularFile(candidate) && !id.equals(inProgressId)) {
                return new BackupFile(candidate, candidate.getFileName().toString());
            }
        }
        throw new BackupNotFoundException(id);
    }

    public void excluir(final String id) {
        final BackupFile file = arquivo(id);
        try {
            Files.deleteIfExists(file.path());
            Files.deleteIfExists(properties.dir().resolve(id + META));
            logger.info("Backup {} excluído", id);
        } catch (IOException e) {
            logger.error("Falha ao excluir o backup {}", id, e);
            throw new BackupFailedException("Não foi possível excluir o backup. Veja os logs do serviço.");
        }
    }

    private String validatedPassphrase(final BackupRequest request) {
        final String passphrase = request == null || request.passphrase() == null || request.passphrase().isBlank() ? null : request.passphrase();
        if (passphrase == null) {
            return null;
        }
        if (passphrase.length() < properties.minPassphraseLength()) {
            throw new InvalidBackupRequestException("A senha de cifra deve ter pelo menos " + properties.minPassphraseLength() + " caracteres");
        }
        if (!passphrase.equals(request.passphraseConfirmation())) {
            throw new InvalidBackupRequestException("A confirmação da senha não confere");
        }
        return passphrase;
    }

    private void dump(final Path target) throws IOException {
        final var pg = properties.pg();
        final List<String> command = List.of(properties.pgDumpBinary(),
                "-h", pg.host(), "-p", String.valueOf(pg.port()), "-U", pg.user(), "-d", pg.database(),
                "--format=custom", "--compress=9", "--file=" + target);
        final var result = runner.run(command, Map.of("PGPASSWORD", pg.password()), null);
        if (result.exitCode() != 0) {
            logger.error("pg_dump terminou com código {}: {}", result.exitCode(), result.output());
            throw new BackupFailedException("Não foi possível gerar o backup. Veja os logs do serviço.");
        }
    }

    private void encrypt(final Path plain, final Path target, final String passphrase) throws IOException {
        // A senha vai pelo stdin ("-pass stdin"), nunca na linha de comando nem no ambiente.
        final List<String> command = List.of(properties.opensslBinary(), "enc", "-aes-256-cbc", "-pbkdf2",
                "-iter", String.valueOf(PBKDF2_ITERATIONS), "-salt", "-in", plain.toString(), "-out", target.toString(), "-pass", "stdin");
        final var result = runner.run(command, Map.of(), passphrase + "\n");
        if (result.exitCode() != 0) {
            logger.error("openssl terminou com código {}: {}", result.exitCode(), result.output());
            throw new BackupFailedException("Não foi possível gerar o backup. Veja os logs do serviço.");
        }
    }

    private void writeMeta(final Path file, final Instant createdAt, final String createdBy, final boolean encrypted, final String sha256) throws IOException {
        final Map<String, Object> meta = new HashMap<>();
        meta.put("createdAt", createdAt.toString());
        meta.put("createdBy", createdBy);
        meta.put("encrypted", encrypted);
        meta.put("sha256", sha256);
        mapper.writeValue(file.toFile(), meta);
    }

    private BackupInfo describe(final Path file) {
        final String name = file.getFileName().toString();
        final String id = idOf(name);
        final boolean encrypted = name.endsWith(ENCRYPTED);
        Instant createdAt = null;
        String createdBy = null;
        String sha256 = null;
        final Path metaFile = properties.dir().resolve(id + META);
        if (Files.isRegularFile(metaFile)) {
            try {
                final JsonNode meta = mapper.readTree(metaFile.toFile());
                createdAt = meta.hasNonNull("createdAt") ? Instant.parse(meta.get("createdAt").asText()) : null;
                createdBy = meta.hasNonNull("createdBy") ? meta.get("createdBy").asText() : null;
                sha256 = meta.hasNonNull("sha256") ? meta.get("sha256").asText() : null;
            } catch (IOException | RuntimeException e) {
                logger.warn("Metadados ilegíveis para {}: {}", id, e.getMessage());
            }
        }
        if (createdAt == null) {
            try {
                createdAt = Files.getLastModifiedTime(file).toInstant();
            } catch (IOException e) {
                createdAt = Instant.EPOCH;
            }
        }
        return toInfo(id, file, createdAt, createdBy, encrypted, sha256);
    }

    private BackupInfo toInfo(final String id, final Path file, final Instant createdAt, final String createdBy,
                              final boolean encrypted, final String sha256) {
        final String fileName = file.getFileName().toString();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        return new BackupInfo(id, fileName, displayPath(file, fileName), size, sha256, createdAt, createdBy, encrypted);
    }

    /** Caminho no host quando configurado; senão o do container (que o usuário não alcança, mas ao menos é verdadeiro). */
    private String displayPath(final Path file, final String fileName) {
        final String hostDir = properties.hostDir();
        if (hostDir == null || hostDir.isBlank()) {
            return file.toString();
        }
        return (hostDir.endsWith("/") ? hostDir.substring(0, hostDir.length() - 1) : hostDir) + "/" + fileName;
    }

    /** Id (nome sem extensão) se o arquivo é um dump deste serviço; {@code null} caso contrário (meta, lixo...). */
    private static String idOf(final String fileName) {
        for (final String suffix : List.of(ENCRYPTED, DUMP)) {
            if (fileName.endsWith(suffix)) {
                final String id = fileName.substring(0, fileName.length() - suffix.length());
                return VALID_ID.matcher(id).matches() ? id : null;
            }
        }
        return null;
    }

    private static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private static void cleanUp(final Path... files) {
        for (final Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                logger.warn("Não foi possível remover {}", file);
            }
        }
    }
}
