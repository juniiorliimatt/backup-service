package br.com.backup.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.backup.config.BackupProperties;
import br.com.backup.exceptions.BackupFailedException;
import br.com.backup.exceptions.BackupInProgressException;
import br.com.backup.exceptions.BackupNotFoundException;
import br.com.backup.exceptions.InvalidBackupRequestException;
import br.com.backup.models.BackupRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:15:00Z");
    private static final String PASSPHRASE = "uma-senha-bem-forte-123";

    @TempDir
    Path dir;

    private FakeRunner runner;
    private BackupService service;

    @BeforeEach
    void setUp() {
        runner = new FakeRunner();
        service = newService("/home/jr/work/projetos/workbox/backups");
    }

    private BackupService newService(final String hostDir) {
        final var props = new BackupProperties(dir, hostDir, "pg_dump", "openssl",
                new BackupProperties.Pg("postgres", 5432, "workbox", "backup_service", "segredo-do-banco"), 12);
        return new BackupService(props, runner, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Executa "no lugar" do pg_dump/openssl: cria os arquivos que os binários criariam e registra as chamadas. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Map<String, String>> environments = new ArrayList<>();
        final List<String> stdins = new ArrayList<>();
        int pgDumpExit = 0;
        Runnable duringDump = () -> { };

        @Override
        public CommandResult run(final List<String> command, final Map<String, String> environment, final String stdin) throws IOException {
            commands.add(command);
            environments.add(new HashMap<>(environment));
            stdins.add(stdin);
            if (command.get(0).equals("pg_dump")) {
                duringDump.run();
                if (pgDumpExit != 0) {
                    return new CommandResult(pgDumpExit, "pg_dump: erro de conexão com senha segredo-do-banco");
                }
                final var file = command.stream().filter(a -> a.startsWith("--file=")).findFirst().orElseThrow().substring("--file=".length());
                Files.write(Path.of(file), "PGDMP-conteudo".getBytes(StandardCharsets.UTF_8));
                return new CommandResult(0, "");
            }
            final var out = command.get(command.indexOf("-out") + 1);
            Files.write(Path.of(out), "SALTED__cifrado".getBytes(StandardCharsets.UTF_8));
            return new CommandResult(0, "");
        }
    }

    @Test
    void gerar_semSenha_criaODumpComMetadadosEChecksum() throws Exception {
        final var info = service.gerar("qa.admin@workbox.local", new BackupRequest(null, null));

        assertThat(info.id()).isEqualTo("workbox_20261004_101500");
        assertThat(info.fileName()).isEqualTo("workbox_20261004_101500.dump");
        assertThat(info.encrypted()).isFalse();
        assertThat(info.createdBy()).isEqualTo("qa.admin@workbox.local");
        assertThat(info.createdAt()).isEqualTo(NOW);
        assertThat(info.sizeBytes()).isEqualTo("PGDMP-conteudo".length());
        assertThat(info.sha256()).hasSize(64).matches("[0-9a-f]+");
        assertThat(info.path()).isEqualTo("/home/jr/work/projetos/workbox/backups/workbox_20261004_101500.dump");
        assertThat(dir.resolve("workbox_20261004_101500.dump")).exists();
        assertThat(runner.commands).hasSize(1);
    }

    @Test
    void gerar_chamaOPgDumpComoCustomComprimidoSemPorAsSenhasNaLinhaDeComando() throws Exception {
        service.gerar("admin", new BackupRequest(null, null));

        final var command = runner.commands.get(0);
        assertThat(command).contains("--format=custom", "--compress=9", "-h", "postgres", "-U", "backup_service", "-d", "workbox");
        assertThat(String.join(" ", command)).doesNotContain("segredo-do-banco");
        assertThat(runner.environments.get(0)).containsEntry("PGPASSWORD", "segredo-do-banco");
    }

    @Test
    void gerar_comSenha_cifraComOpensslLendoASenhaDoStdinEApagaOArquivoSemCifra() throws Exception {
        final var info = service.gerar("admin", new BackupRequest(PASSPHRASE, PASSPHRASE));

        assertThat(info.encrypted()).isTrue();
        assertThat(info.fileName()).isEqualTo("workbox_20261004_101500.dump.enc");
        assertThat(dir.resolve("workbox_20261004_101500.dump.enc")).exists();
        assertThat(dir.resolve("workbox_20261004_101500.dump")).doesNotExist();
        final var openssl = runner.commands.get(1);
        assertThat(openssl).contains("enc", "-aes-256-cbc", "-pbkdf2", "-salt", "-pass", "stdin");
        assertThat(String.join(" ", openssl)).doesNotContain(PASSPHRASE);
        assertThat(runner.stdins.get(1)).isEqualTo(PASSPHRASE + "\n");
        assertThat(info.sizeBytes()).isEqualTo("SALTED__cifrado".length());
    }

    @Test
    void gerar_senhaCurta_ehRecusadaAntesDeRodarQualquerComando() {
        assertThatThrownBy(() -> service.gerar("admin", new BackupRequest("curta", "curta")))
                .isInstanceOf(InvalidBackupRequestException.class).hasMessageContaining("12");
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void gerar_confirmacaoDiferente_ehRecusada() {
        assertThatThrownBy(() -> service.gerar("admin", new BackupRequest(PASSPHRASE, PASSPHRASE + "x")))
                .isInstanceOf(InvalidBackupRequestException.class);
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void gerar_senhaEmBranco_contaComoSemSenha() throws Exception {
        final var info = service.gerar("admin", new BackupRequest("   ", ""));

        assertThat(info.encrypted()).isFalse();
    }

    @Test
    void gerar_quandoOPgDumpFalha_naoDeixaArquivoNemVazaAMensagemDoBinario() {
        runner.pgDumpExit = 1;

        assertThatThrownBy(() -> service.gerar("admin", new BackupRequest(null, null)))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageNotContaining("segredo-do-banco");
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void gerar_duasAoMesmoTempo_aSegundaRecebeConflito() throws Exception {
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        runner.duringDump = () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        final var pool = Executors.newSingleThreadExecutor();
        try {
            final var first = pool.submit(() -> service.gerar("admin", new BackupRequest(null, null)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.gerar("outro", new BackupRequest(null, null))).isInstanceOf(BackupInProgressException.class);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo("workbox_20261004_101500");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void gerar_depoisDeUmaFalha_liberaOTravaEPermiteNovoBackup() throws Exception {
        runner.pgDumpExit = 1;
        assertThatThrownBy(() -> service.gerar("admin", new BackupRequest(null, null))).isInstanceOf(BackupFailedException.class);

        runner.pgDumpExit = 0;
        assertThat(service.gerar("admin", new BackupRequest(null, null)).id()).isNotNull();
    }

    @Test
    void listar_devolveOsMaisNovosPrimeiroELeOsMetadadosDoDisco() throws Exception {
        service.gerar("admin", new BackupRequest(null, null));
        final var later = new BackupService(new BackupProperties(dir, "", "pg_dump", "openssl",
                new BackupProperties.Pg("postgres", 5432, "workbox", "u", "p"), 12), runner,
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        later.gerar("admin2", new BackupRequest(PASSPHRASE, PASSPHRASE));

        final var list = later.listar();

        assertThat(list).extracting(b -> b.id()).containsExactly("workbox_20261004_111500", "workbox_20261004_101500");
        assertThat(list.get(0).encrypted()).isTrue();
        assertThat(list.get(0).createdBy()).isEqualTo("admin2");
        assertThat(list.get(1).encrypted()).isFalse();
    }

    @Test
    void listar_ignoraArquivosEstranhosNaPasta() throws Exception {
        Files.writeString(dir.resolve("lixo.txt"), "x");
        Files.writeString(dir.resolve("workbox_20260101_000000.dump"), "dump sem metadados");

        final var list = service.listar();

        assertThat(list).extracting(b -> b.id()).containsExactly("workbox_20260101_000000");
        assertThat(list.get(0).createdBy()).isNull();
        assertThat(list.get(0).sizeBytes()).isEqualTo("dump sem metadados".length());
    }

    @Test
    void listar_semPastaOuVazia_devolveListaVazia() {
        assertThat(service.listar()).isEmpty();
    }

    @Test
    void arquivo_devolveOCaminhoDoDump() throws Exception {
        service.gerar("admin", new BackupRequest(null, null));

        final var file = service.arquivo("workbox_20261004_101500");

        assertThat(file.fileName()).isEqualTo("workbox_20261004_101500.dump");
        assertThat(file.path()).isEqualTo(dir.resolve("workbox_20261004_101500.dump"));
    }

    @Test
    void arquivo_idInexistenteOuComPathTraversal_ehNaoEncontrado() {
        assertThatThrownBy(() -> service.arquivo("workbox_20990101_000000")).isInstanceOf(BackupNotFoundException.class);
        assertThatThrownBy(() -> service.arquivo("../etc/passwd")).isInstanceOf(BackupNotFoundException.class);
        assertThatThrownBy(() -> service.arquivo("..%2F..%2Fetc")).isInstanceOf(BackupNotFoundException.class);
        assertThatThrownBy(() -> service.arquivo("workbox_20261004_101500/../../x")).isInstanceOf(BackupNotFoundException.class);
    }

    @Test
    void excluir_removeODumpEOSidecar() throws Exception {
        service.gerar("admin", new BackupRequest(null, null));

        service.excluir("workbox_20261004_101500");

        assertThat(dir).isEmptyDirectory();
        assertThat(service.listar()).isEmpty();
    }

    @Test
    void excluir_idInexistente_ehNaoEncontrado() {
        assertThatThrownBy(() -> service.excluir("workbox_20990101_000000")).isInstanceOf(BackupNotFoundException.class);
    }

    @Test
    void caminhoExibido_semHostDirConfigurado_usaOCaminhoDoContainer() throws Exception {
        final var info = newService("").gerar("admin", new BackupRequest(null, null));

        assertThat(info.path()).isEqualTo(dir.resolve("workbox_20261004_101500.dump").toString());
    }
}
