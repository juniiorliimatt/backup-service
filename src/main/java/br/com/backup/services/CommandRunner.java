package br.com.backup.services;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Executa um binário externo (pg_dump, openssl). Existe como interface pra o {@link BackupService} ser testado sem
 * os binários: o teste usa um runner que só cria os arquivos que eles criariam.
 */
public interface CommandRunner {

    /**
     * @param command     binário e argumentos — nunca coloque senhas aqui (aparecem em {@code ps})
     * @param environment variáveis a somar ao ambiente do processo (ex.: {@code PGPASSWORD})
     * @param stdin       texto enviado ao stdin do processo, ou {@code null}
     */
    CommandResult run(List<String> command, Map<String, String> environment, String stdin) throws IOException;

    /** {@code output} junta stdout e stderr (truncado) — só pra log, nunca devolvido ao cliente. */
    record CommandResult(int exitCode, String output) {
    }
}
