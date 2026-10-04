# backup-service — instruções do serviço

> Complementa o [`CLAUDE.md` da raiz](../CLAUDE.md) e as regras globais de `~/.claude/CLAUDE.md`.
> Uso/endpoints: [`README.md`](README.md).

## Papel e autoria
- Gera, lista, entrega (download) e apaga backups do **Postgres** (`pg_dump --format=custom`, banco
  `workbox` inteiro, todos os schemas). *Resource server*: valida o Bearer do `workbox-api` por
  introspecção remota. Só o papel **ADMIN** entra (não é por módulo): um backup tem hashes de senha e
  segredos de MFA em texto plano.
- Implementação do Claude Code, autorizada pelo desenvolvedor em 2026-10-04 (serviço novo volta ao
  padrão "consultor" se a autorização cair — ver raiz).
- **Restore NÃO existe aqui, de propósito** (decisão do desenvolvedor): só por `scripts/restore-db.sh`.
  Nunca adicionar endpoint/botão de restore sem pedido explícito.
- Mongo (notes-service) e agendamento ficam para uma fase posterior.

## Stack e execução
- Java 25, Spring Boot 3.5.16, Gradle (`./gradlew`), sem JPA/JDBC/Liquibase: **não tem tabela**. A pasta de
  backups é a fonte da verdade (arquivo + `.meta.json` ao lado com quem pediu, sha256 e se é cifrado).
- Porta 7058 (container 8084). Imagem alpine com `postgresql18-client` (mesma major do servidor) e `openssl`.
- `./gradlew test`, `./gradlew generateOpenApiDocs` (porta 7099, pasta temporária, não precisa de Postgres).

## Regras e armadilhas
- **Senhas nunca na linha de comando** (aparecem em `ps`): `PGPASSWORD` vai por ambiente do processo e a
  senha de cifra por **stdin** (`openssl ... -pass stdin`). `BackupRequest.toString` e `BackupProperties.Pg.toString`
  mascaram segredos; nada de logar o corpo da requisição.
- Cifra opcional: `openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt` → `.dump.enc`. Decifrar com
  `scripts/decrypt-backup.sh` (pede a senha no terminal). CBC não autentica: a integridade vem do SHA-256 do
  arquivo (nos metadados) e do próprio `pg_restore`. Senha mínima de 12 caracteres, nunca gravada.
- O dump em claro existe por instantes na pasta antes de ser cifrado e apagado (`gerar`); falha limpa tudo.
- **Um backup por vez** (`ReentrantLock`; o segundo recebe 409). Geração síncrona — com o banco na casa de
  dezenas de MB leva segundos; se crescer, trocar por 202 + polling (cuidado com timeout do nginx).
- O id do backup (`<banco>_<yyyyMMdd_HHmmss>` em UTC) vira nome de arquivo: validado por `^[A-Za-z0-9_]+$` e
  normalizado dentro da pasta (path traversal → 404). Arquivo fora do padrão é ignorado na listagem.
- Erros ao cliente são genéricos (`BackupFailedException`); a saída do `pg_dump`/`openssl` só vai pro log.
- `backup.host-dir` (env `BACKUP_HOST_DIR`) é só o caminho **no host** exibido na tela; o serviço grava em
  `backup.dir` (`/backups`). Sem ele, a tela mostra o caminho do container.
- Role Postgres dedicado `backup_service` com `pg_read_all_data` (não o superusuário) — criado fora do
  Liquibase (este serviço não tem banco próprio), ver README.

## Testes (test-first)
- `BackupServiceTest` (runner falso que cria os arquivos que pg_dump/openssl criariam, `@TempDir`),
  `BackupControllerTest` (`@WebMvcTest` + `SecurityConfig` real, só o introspector é mockado),
  `WorkboxTokenIntrospectorTest`. Os binários reais só são exercitados no teste manual com o compose.

## Commits
pt-BR, Conventional Commits, conforme o [CLAUDE.md da raiz](../CLAUDE.md#convenção-de-mensagens-de-commit).
Branch `develop`; push só com confirmação.
