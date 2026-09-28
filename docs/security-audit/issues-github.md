# Issues para o GitHub — Relatório de Auditoria de Segurança — SolarSync

--- ISSUE 1 ---
Título: [Segurança] GESTOR consegue reativar e assumir a conta técnica de integração
Labels: security, severity:medium
Achados: A-01

## Descrição
A única fronteira da gestão de usuários no servidor é "o alvo é ADMINISTRADOR?". A conta técnica `integracao@conectsol.com` (semeada pela V13 com `ativo = false`, sem senha e sem papel) passa por essa guarda. Um GESTOR pode reativá-la, definir uma senha, dar-lhe um papel e entrar por ela. A tela de Usuários mostra o botão "Reativar" para essa conta.

A mesma rota permite ao GESTOR redefinir a senha de outro GESTOR e agir em nome dele, e nenhuma operação de gestão de usuários gera trilha de auditoria.

## Por que é explorável
1. `POST /api/usuarios/{idIntegracao}/ativar`
2. `POST /api/usuarios/{idIntegracao}/senha` com `{"senha": "..."}`
3. `PUT /api/usuarios/{idIntegracao}` com `papeis: ["GESTOR"]`
4. `POST /api/auth/login` como `integracao@conectsol.com`

Toda transição feita depois disso fica no `historico_status` como "Integração automática", indistinguível da automação do Gmail/Nectar.

## Evidência
- `src/main/java/com/conectsol/solarsync/auth/UsuarioService.java:120-127`
```java
private static void exigirPoderSobre(Usuario alvo, UsuarioAutenticado autor) {
    boolean alvoEhAdmin = alvo.getPapeis().stream()
            .anyMatch(papel -> papel.getNome() == NomePapel.ADMINISTRADOR);
    if (alvoEhAdmin && !autor.tem(NomePapel.ADMINISTRADOR)) {
        throw new AccessDeniedException("Só um ADMINISTRADOR altera outro ADMINISTRADOR");
    }
}
```
- `src/main/java/com/conectsol/solarsync/auth/UsuarioService.java:81-98` (`definirSenha`, `alterarAtivacao` só chamam a guarda acima)
- `src/main/resources/db/migration/V13__integracoes_nectar_e_gmail.sql:68-69`
- `solarsync-front/src/components/modules/UsuariosModule.tsx:140` (`podeMexerEm` só exclui administradores), `:230`, `:396` (botão Reativar)

## Impacto
Integridade e repúdio: aprovações, reprovações e mudanças de status atribuídas à automação, e a mesma coisa em nome de outro gestor. Sem escalada para ADMINISTRADOR.

## Sugestão de correção
- Tratar a conta técnica como conta de sistema (coluna `conta_sistema` ou comparação com `UsuarioIntegracao.EMAIL`) e recusar com 403, no `UsuarioService`, `ativar`, `definirSenha` e `atualizar` para ela, qualquer que seja o papel do autor.
- Recusar o login dessa conta no `LoginSenhaService`, mesmo com senha definida.
- Esconder a conta da tela de Usuários.
- Registrar em auditoria quem redefiniu a senha, ativou/desativou ou mudou os papéis de quem.

## Critérios de aceite
- [ ] GESTOR e ADMINISTRADOR recebem 403 ao ativar, definir senha ou editar papéis de `integracao@conectsol.com`
- [ ] Login com `integracao@conectsol.com` falha mesmo se `senha_hash` estiver preenchido
- [ ] A conta não aparece na listagem da tela de Usuários
- [ ] Redefinir senha, ativar/desativar e mudar papéis gravam autor, alvo e instante numa trilha consultável
- [ ] Teste de integração cobrindo os quatro passos da exploração acima
--- FIM ISSUE 1 ---

--- ISSUE 2 ---
Título: [Segurança] Validar segredos no boot e remover credenciais fixas de dev
Labels: security, severity:low
Achados: A-02, A-03

## Descrição
Nenhum segredo real está no repositório, mas três pontos deixam a segurança depender de alguém lembrar de configurar certo:

1. Com `SPRING_PROFILES_ACTIVE=prod`, a aplicação sobe sem `SOLARSYNC_JWT_SEGREDO`, só com um WARN, usando um segredo aleatório.
2. `SOLARSYNC_ADMIN_SENHA_INICIAL` é aplicada ao administrador sem a política de tamanho mínimo que o `DefinirSenhaRequest` exige no resto do sistema (`123` é aceita).
3. O `compose.yaml` de desenvolvimento tem a senha `solarsync_dev` commitada e publica a 5432 em todas as interfaces do host.

## Por que é explorável
- (2) Uma senha fraca de administrador fica exposta ao login público, mitigado só pelo limite de 10 falhas/15 min por e-mail.
- (3) Em rede compartilhada, qualquer máquina alcança o Postgres de dev com a senha pública.
- (1) Não abre acesso, mas desloga todos a cada restart sem nada acusar.

## Evidência
- `src/main/resources/application.properties:16` — `solarsync.jwt.segredo=${SOLARSYNC_JWT_SEGREDO:}`
- `src/main/java/com/conectsol/solarsync/auth/jwt/JwtConfig.java:44-52` — fallback para segredo aleatório
- `src/main/java/com/conectsol/solarsync/auth/AdminBootstrap.java:64` — `admin.setSenhaHash(passwordEncoder.encode(senhaInicial));`
- `compose.yaml:7-9`
```yaml
- 'POSTGRES_PASSWORD=solarsync_dev'
ports:
  - '5432'
```

## Impacto
Baixo: depende de configuração errada no servidor ou de rede de dev exposta.

## Sugestão de correção
- No perfil `prod`, falhar o boot se `solarsync.jwt.segredo` estiver vazio.
- No `AdminBootstrap`, exigir o mesmo mínimo do `DefinirSenhaRequest` (idealmente 12+) e recusar o boot com senha fraca.
- No `compose.yaml`, publicar como `'127.0.0.1::5432'` e ler a senha de `${POSTGRES_PASSWORD_DEV:-solarsync_dev}`.

## Critérios de aceite
- [ ] `SPRING_PROFILES_ACTIVE=prod` sem `SOLARSYNC_JWT_SEGREDO` falha no boot com mensagem clara
- [ ] `SOLARSYNC_ADMIN_SENHA_INICIAL` com menos que o mínimo falha no boot
- [ ] `docker compose up` de dev não escuta a 5432 fora do loopback (`docker port` mostra 127.0.0.1)
- [ ] Testes cobrindo os dois fail-fast
--- FIM ISSUE 2 ---

--- ISSUE 3 ---
Título: [Segurança] Adicionar Content-Security-Policy e reduzir a exposição dos tokens
Labels: security, severity:low
Achados: A-04

## Descrição
Não há nenhum sink de XSS no frontend hoje. Mas o access token e o refresh token (8 h) ficam em `localStorage`, legíveis por qualquer script da origem, e o Caddy não envia `Content-Security-Policy`. Uma injeção futura, ou uma dependência comprometida, entregaria as duas credenciais sem nada que a contivesse.

## Por que é explorável
Não é explorável sozinho: exige antes uma falha de XSS. É defesa em profundidade para limitar o estrago desse cenário.

## Evidência
- `deploy/Caddyfile:53-61` — bloco `header` sem CSP
- `solarsync-front/src/api/client.ts:28-38`
```ts
localStorage.setItem(CHAVE_ACCESS, accessToken);
localStorage.setItem(CHAVE_REFRESH, refreshToken);
```

## Impacto
Roubo de sessão por até 8 h (ou até o usuário ser desativado) caso surja um XSS.

## Sugestão de correção
Adicionar ao bloco `header` do Caddyfile, primeiro como `Content-Security-Policy-Report-Only`:
```
default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com;
font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self';
object-src 'none'; base-uri 'self'; frame-ancestors 'none'
```
Depois de validar a interface, trocar para `Content-Security-Policy`. A médio prazo, avaliar levar o refresh token para cookie `HttpOnly; Secure; SameSite=Strict` (mesmo domínio já elimina CORS).

## Critérios de aceite
- [ ] Resposta de `/` e de `/api/*` traz `Content-Security-Policy` com `script-src 'self'` e `frame-ancestors 'none'`
- [ ] Todas as telas funcionam sem violação no console do navegador
- [ ] Um `<script>` inline injetado em teste manual é bloqueado pelo navegador
--- FIM ISSUE 3 ---

--- ISSUE 4 ---
Título: [Segurança] Reavaliar a janela de 15 min para revogar acesso e papel
Labels: security, severity:informational
Achados: A-05

## Descrição
Os papéis ficam gravados no access token e o servidor não relê o usuário a cada requisição. Desativar alguém ou tirar o papel GESTOR corta a renovação na hora, mas o access token já emitido vale até 15 minutos. Decisão documentada e aceita (CLAUDE.md §10); esta issue existe para ser reavaliada, não necessariamente corrigida.

## Evidência
- `src/main/java/com/conectsol/solarsync/auth/jwt/TokenService.java:62` — `.claim(CLAIM_PAPEIS, papeis)`
- `src/main/java/com/conectsol/solarsync/auth/jwt/JwtConfig.java:75` — validação só criptográfica

## Impacto
Janela de até 15 min para um usuário recém-desligado agir com o papel antigo.

## Sugestão de correção
Se virar requisito: um `token_version` por usuário no JWT, conferido contra um cache de curta duração, incrementado ao desativar ou ao mudar papéis.

## Critérios de aceite
- [ ] Decisão registrada (manter ou implementar)
- [ ] Se implementar: requisição com token de usuário recém-desativado responde 401 imediatamente
--- FIM ISSUE 4 ---
