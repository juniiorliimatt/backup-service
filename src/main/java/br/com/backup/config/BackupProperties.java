package br.com.backup.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prefixo {@code backup}. {@code dir} é onde o serviço grava (dentro do container); {@code hostDir} é só o caminho
 * equivalente no host, exibido na tela pra você achar o arquivo. {@code pg} é o role dedicado do dump
 * ({@code pg_read_all_data}); a senha vem por env e nunca vai pra linha de comando nem pra log.
 */
@ConfigurationProperties(prefix = "backup")
public record BackupProperties(Path dir, String hostDir, String pgDumpBinary, String opensslBinary, Pg pg, int minPassphraseLength) {

    public record Pg(String host, int port, String database, String user, String password) {

        /** Sem a senha: o record é impresso em logs de configuração por engano com facilidade. */
        @Override
        public String toString() {
            return "Pg[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user + ", password=***]";
        }
    }
}
