"""
Gera docs/security-audit/relatorio-auditoria-seguranca.pdf.

Uso (ambiente isolado, nada global):
    python -m venv .venv-auditoria
    .venv-auditoria/Scripts/python -m pip install -r docs/security-audit/requirements.txt   (Windows)
    .venv-auditoria/bin/python -m pip install -r docs/security-audit/requirements.txt       (Linux/macOS)
    <python do venv> docs/security-audit/gerar_relatorio.py

Os achados vivem em ACHADOS / PONTOS_FORTES / ISSUES abaixo: para regerar depois de uma
correção, edite os dados e rode de novo. Também escreve issues-github.md ao lado do PDF.
"""
from __future__ import annotations

import os
import tempfile
from xml.sax.saxutils import escape

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402
from reportlab.lib import colors  # noqa: E402
from reportlab.lib.enums import TA_CENTER  # noqa: E402
from reportlab.lib.pagesizes import A4  # noqa: E402
from reportlab.lib.styles import ParagraphStyle  # noqa: E402
from reportlab.lib.units import cm  # noqa: E402
from reportlab.pdfbase import pdfmetrics  # noqa: E402
from reportlab.pdfbase.ttfonts import TTFont  # noqa: E402
from reportlab.platypus import (  # noqa: E402
    BaseDocTemplate, Frame, Image, KeepTogether, NextPageTemplate, PageBreak, PageTemplate,
    Paragraph, Spacer, Table, TableStyle,
)

AQUI = os.path.dirname(os.path.abspath(__file__))
SAIDA_PDF = os.path.join(AQUI, "relatorio-auditoria-seguranca.pdf")
SAIDA_MD = os.path.join(AQUI, "issues-github.md")
PACOTE = "src/main/java/com/conectsol/solarsync/"

PROJETO = "SolarSync"
TITULO = f"Relatório de Auditoria de Segurança — {PROJETO}"
DATA = "24/09/2026"

# --------------------------------------------------------------------------- paleta
COR = {
    "critica": "#B91C1C", "alta": "#EA580C", "media": "#D97706", "baixa": "#2563EB",
    "informativa": "#6B7280", "forte": "#059669",
}
ROTULO = {
    "critica": "Crítica", "alta": "Alta", "media": "Média", "baixa": "Baixa",
    "informativa": "Informativa",
}
ORDEM_SEV = ["critica", "alta", "media", "baixa", "informativa"]
VERDE_ESCURO = colors.HexColor("#244F26")
CINZA_TEXTO = colors.HexColor("#424342")
CINZA_CLARO = colors.HexColor("#F3F4F6")
BORDA = colors.HexColor("#E5E7EB")

CATEGORIAS = [
    (1, "Banco sem tranca"),
    (2, "Permissão no navegador"),
    (3, "IDOR"),
    (4, "Chaves expostas"),
    (5, "Inputs sem tratamento (XSS)"),
]

# --------------------------------------------------------------------------- dados
METODOLOGIA = [
    ("Stack detectada",
     "Backend Java 21 + Spring Boot 4.1 (WebMVC, Security, Data JPA/Hibernate, Flyway) sobre "
     "PostgreSQL 16; autenticação JWT HS256 própria (resource server do Spring) e RBAC via "
     "@PreAuthorize em meta-anotações. Frontend React 18 + TypeScript + Vite + Tailwind "
     "(repositório solarsync-front). Deploy: Dockerfile multi-stage, Docker Compose e Caddy "
     "(TLS + proxy reverso). Não há CI, Helm nem Terraform no repositório."),
    ("1. Banco sem tranca",
     "Sistema de uma única empresa (single-tenant), sem Supabase/RLS. O mecanismo de isolamento "
     "é apenas o papel do usuário: por decisão documentada (CLAUDE.md §4 e §8, itens 4 e 5), "
     "ANALISTA vê todos os clientes. Verificou-se que não há coluna de dono que deveria filtrar "
     "consultas e que listagens, buscas e agregações do dashboard só são alcançáveis autenticado."),
    ("2. Permissão no navegador",
     "Cruzados os gates do frontend (Sidebar, UsuariosModule, temPapel) com os 70 handlers REST "
     "e com as guardas de hierarquia do UsuarioService."),
    ("3. IDOR",
     "Percorridos todos os 70 handlers dos 10 controllers. Como não há posse por usuário, acesso "
     "por id sem checagem de dono é o comportamento especificado; a análise concentrou-se onde "
     "há hierarquia de objeto (usuários e conta técnica) e na origem do autor da auditoria."),
    ("4. Chaves expostas",
     "Busca por padrões de segredo e strings de alta entropia na árvore e em todo o histórico "
     "git dos dois repositórios (24 + 13 commits), em configs, compose, Dockerfile, scripts, "
     "documentação, testes e no bundle dist/ do frontend; revisão de defaults ${VAR:-...} e da "
     "validação de startup."),
    ("5. XSS",
     "Busca por dangerouslySetInnerHTML, innerHTML, insertAdjacentHTML, document.write, eval, "
     "new Function, href/src dinâmicos, window.open e bibliotecas de markdown no frontend; no "
     "backend, geração de HTML (e-mails, templates) e cabeçalhos de segurança do proxy."),
]

ACHADOS = [
    {
        "id": "A-01", "cat": 2, "sev": "media",
        "titulo": "GESTOR reativa e assume a conta técnica de integração",
        "local": [
            "src/main/java/com/conectsol/solarsync/auth/UsuarioService.java:120-127",
            "src/main/java/com/conectsol/solarsync/auth/UsuarioService.java:81-98",
            "src/main/resources/db/migration/V13__integracoes_nectar_e_gmail.sql:68-69",
            "solarsync-front/src/components/modules/UsuariosModule.tsx:140, 230, 396",
        ],
        "trecho": (
            "// UsuarioService.java:121-126\n"
            "private static void exigirPoderSobre(Usuario alvo, UsuarioAutenticado autor) {\n"
            "    boolean alvoEhAdmin = alvo.getPapeis().stream()\n"
            "            .anyMatch(papel -> papel.getNome() == NomePapel.ADMINISTRADOR);\n"
            "    if (alvoEhAdmin && !autor.tem(NomePapel.ADMINISTRADOR)) { throw ... }\n"
            "}\n"
            "-- V13:68-69\n"
            "INSERT INTO usuario (nome, email, ativo)\n"
            "VALUES ('Integração automática', 'integracao@conectsol.com', FALSE);"),
        "descricao": (
            "A única fronteira da gestão de usuários no servidor é \"o alvo é ADMINISTRADOR?\". A "
            "conta técnica integracao@conectsol.com não tem papel nenhum, então passa pela guarda: "
            "um GESTOR pode chamar POST /api/usuarios/{id}/ativar, POST /{id}/senha e PUT /{id} "
            "com papeis=[GESTOR], e depois entrar por ela. A tela oferece o botão \"Reativar\" para "
            "essa conta (podeMexerEm só exclui administradores). A mesma rota permite ao GESTOR "
            "redefinir a senha de outro GESTOR e agir em nome dele, e nenhuma operação de gestão "
            "de usuários gera trilha de auditoria (o UsuarioService não publica evento)."),
        "explorabilidade": (
            "Exige uma conta GESTOR (papel confiável). Não há escalada para ADMINISTRADOR. O "
            "impacto é de integridade e repúdio: aprovações, reprovações e transições feitas pela "
            "conta ficam no historico_status como \"Integração automática\", indistinguíveis da "
            "automação do Gmail/Nectar — exatamente a trilha usada para responder \"por que este "
            "projeto foi reprovado\"."),
        "correcao": (
            "Marcar a conta técnica como conta de sistema (coluna ou comparação com "
            "UsuarioIntegracao.EMAIL) e recusar, no UsuarioService, ativar, definir senha e editar "
            "papéis dela para qualquer papel; recusar também no LoginSenhaService. Esconder a conta "
            "da tela de Usuários. Registrar em auditoria quem redefiniu a senha, ativou/desativou "
            "ou mudou papéis de quem."),
    },
    {
        "id": "A-02", "cat": 4, "sev": "baixa",
        "titulo": "Sem validação de startup para segredos em produção",
        "local": [
            "src/main/resources/application.properties:16",
            "src/main/java/com/conectsol/solarsync/auth/jwt/JwtConfig.java:44-52",
            "src/main/java/com/conectsol/solarsync/auth/AdminBootstrap.java:38, 64",
        ],
        "trecho": (
            "# application.properties:16\n"
            "solarsync.jwt.segredo=${SOLARSYNC_JWT_SEGREDO:}\n"
            "// JwtConfig.java:45-51\n"
            "if (segredo == null || segredo.isBlank()) {\n"
            "    log.warn(\"solarsync.jwt.segredo ausente: gerando segredo aleatório...\");\n"
            "    ... new SecureRandom().nextBytes(aleatorio);\n"
            "// AdminBootstrap.java:64  (sem nenhuma regra de tamanho)\n"
            "admin.setSenhaHash(passwordEncoder.encode(senhaInicial));"),
        "descricao": (
            "Não há default público de segredo — o default é vazio e o fallback é aleatório, o que "
            "não é explorável. O problema é a ausência de fail-fast: com SPRING_PROFILES_ACTIVE=prod "
            "a aplicação sobe sem SOLARSYNC_JWT_SEGREDO apenas com um WARN. E a senha inicial do "
            "administrador é aplicada sem a política de 8+ caracteres que o DefinirSenhaRequest "
            "exige no resto do sistema: SOLARSYNC_ADMIN_SENHA_INICIAL=123 é aceita."),
        "explorabilidade": (
            "Depende de configuração errada no servidor. A senha fraca do administrador fica "
            "exposta ao login público, mitigado pelo limite de 10 falhas/15 min por e-mail. O "
            "segredo ausente não abre acesso, mas desloga todos a cada restart sem nada acusar."),
        "correcao": (
            "No perfil prod, falhar o boot se solarsync.jwt.segredo estiver vazio. Exigir no "
            "AdminBootstrap o mesmo mínimo do DefinirSenhaRequest (idealmente 12+) e recusar o "
            "boot com senha fraca."),
    },
    {
        "id": "A-03", "cat": 4, "sev": "baixa",
        "titulo": "Senha fixa do Postgres de dev com porta publicada em todas as interfaces",
        "local": ["compose.yaml:7-9"],
        "trecho": (
            "# compose.yaml:4-9\n"
            "environment:\n"
            "  - 'POSTGRES_PASSWORD=solarsync_dev'\n"
            "ports:\n"
            "  - '5432'"),
        "descricao": (
            "O compose de desenvolvimento (usado pelo suporte a Docker Compose do Spring Boot) tem "
            "senha fixa commitada e publica a 5432 numa porta efêmera do host em 0.0.0.0. O "
            "primeiro commit (66a4492) trazia POSTGRES_PASSWORD=secret."),
        "explorabilidade": (
            "Só em máquina de desenvolvimento com o container de pé e rede compartilhada (Wi-Fi "
            "do escritório, por exemplo) sem firewall bloqueando. Afeta o banco de dev, que pode "
            "receber a importação da planilha real (docs/importacao)."),
        "correcao": (
            "Publicar só no loopback ('127.0.0.1::5432') e ler a senha de variável com default "
            "de dev. Não reaproveitar solarsync_dev em nenhum outro ambiente."),
    },
    {
        "id": "A-04", "cat": 5, "sev": "baixa",
        "titulo": "Sem Content-Security-Policy e tokens em localStorage",
        "local": [
            "deploy/Caddyfile:53-61",
            "solarsync-front/src/api/client.ts:28-38",
        ],
        "trecho": (
            "# Caddyfile:53-61 — não há Content-Security-Policy\n"
            "header {\n"
            "  Strict-Transport-Security \"max-age=31536000\"\n"
            "  X-Content-Type-Options \"nosniff\"\n"
            "  X-Frame-Options \"DENY\"\n"
            "  Referrer-Policy \"same-origin\"\n"
            "// client.ts:32-33\n"
            "localStorage.setItem(CHAVE_ACCESS, accessToken);\n"
            "localStorage.setItem(CHAVE_REFRESH, refreshToken);"),
        "descricao": (
            "Não foi encontrado nenhum sink de XSS no frontend (ver pontos fortes). O achado é de "
            "defesa em profundidade: o access token e o refresh token de 8 h ficam em "
            "localStorage, legíveis por qualquer script da origem, e o proxy não envia CSP. "
            "Uma única injeção futura, ou uma dependência comprometida, entregaria as duas "
            "credenciais sem nada que a contivesse."),
        "explorabilidade": (
            "Não explorável hoje: exige primeiro uma falha de XSS, que não existe no código "
            "atual. O refresh token roubado vale 8 h ou até o usuário ser desativado."),
        "correcao": (
            "Enviar CSP pelo Caddy: default-src 'self'; script-src 'self'; style-src 'self' "
            "https://fonts.googleapis.com; font-src https://fonts.gstatic.com; connect-src 'self'; "
            "img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'. "
            "Validar na interface (começar com Content-Security-Policy-Report-Only). A médio "
            "prazo, levar o refresh token para cookie HttpOnly/SameSite=Strict no mesmo domínio."),
    },
    {
        "id": "A-05", "cat": 2, "sev": "informativa",
        "titulo": "Desativação e rebaixamento de papel valem só após até 15 min",
        "local": [
            "src/main/java/com/conectsol/solarsync/auth/jwt/TokenService.java:62",
            "src/main/java/com/conectsol/solarsync/auth/jwt/JwtConfig.java:75",
        ],
        "trecho": (
            "// TokenService.java:62 — papéis gravados no access token\n"
            ".claim(CLAIM_PAPEIS, papeis)\n"
            "// JwtConfig.java:75 — validação só criptográfica, sem reler o usuário\n"
            "return decoderDoTipo(chaveJwt, propriedades, TokenService.TIPO_ACCESS);"),
        "descricao": (
            "O servidor confia nos papéis e na existência do usuário que estão no JWT até ele "
            "expirar. Desativar alguém ou tirar o papel GESTOR corta a renovação na hora, mas o "
            "access token já emitido continua valendo até 15 minutos. Decisão documentada e "
            "aceita (CLAUDE.md §10)."),
        "explorabilidade": (
            "Janela de até 15 min para um usuário recém-desligado continuar agindo com o papel "
            "antigo."),
        "correcao": (
            "Aceitável como está. Se a janela incomodar: versão de token por usuário (token_version "
            "no JWT, conferida contra cache) ou encurtar o access token."),
    },
]

PONTOS_FORTES = [
    (2, "Todo handler sensível tem guarda no servidor",
     "Dos 70 handlers, 67 têm @PodeLer/@PodeEscrever/@SomenteAdministrador/@GerenciaUsuarios; "
     "os 3 restantes são /api/auth/login e /refresh (públicos por natureza) e /api/auth/eu "
     "(autenticado pelo anyRequest().authenticated() — SecurityConfig.java:65)."),
    (2, "Negação por padrão e conversor de papéis correto",
     "SecurityConfig.java:52-65 (só login, refresh, swagger e health são permitAll) e "
     "SecurityConfig.java:81-90 (claim papeis com prefixo ROLE_, coberto por "
     "PendenciaControllerRbacTest)."),
    (2, "Hierarquia de papéis replicada no servidor",
     "UsuarioService.java:114-127: GESTOR não concede ADMINISTRADOR nem altera um administrador; "
     "UsuarioService.java:93-95: ninguém desativa a própria conta. É o espelho de "
     "UsuariosModule.tsx:140-144, não só UI."),
    (2, "Exclusão só por ADMINISTRADOR, e a tela nem oferece",
     "Os 7 DELETE usam @SomenteAdministrador; o frontend não tem nenhum botão de excluir; "
     "corrigir-status (ProjetoController.java:204-205) também é @SomenteAdministrador."),
    (3, "Autor da auditoria sempre do token, nunca do corpo",
     "UsuarioAutenticado.java:20-27 lê o id do subject do JWT; os controllers passam usuario.id() "
     "aos services. Nenhum request DTO carrega o autor da mudança."),
    (3, "Trilhas sem endpoint de escrita",
     "historico_status e email_coelba só são escritos por listener/job; não há POST/PUT para elas."),
    (1, "Consultas parametrizadas, inclusive o SQL nativo",
     "DashboardRepository.java:31-53 interpola só nomes de coluna constantes; valores entram por "
     "setParameter (linhas 233-235 e 340-343). O restante usa JPA Specifications."),
    (2, "Tokens bem separados e renovação relendo o banco",
     "TipoTokenValidator impede refresh token como bearer; emissor validado; segredo HS256 "
     "exige 32+ bytes (JwtConfig.java:54-59); RenovacaoTokenService.java:34-39 recusa inativo."),
    (2, "Login resistente a enumeração e a força bruta",
     "LoginSenhaService: mesma exceção para todos os motivos e hash falso para igualar o tempo; "
     "ControleDeTentativasDeLogin recusa antes do BCrypt (10/e-mail, 60/origem em 15 min)."),
    (4, "Nenhum segredo real na árvore nem no histórico",
     "24 commits do backend e 13 do frontend varridos: só placeholders (token-de-teste, "
     "GOCSPX-...). .gitignore cobre .env, .env.*, config/ e *.pem; config/application.properties "
     "local nunca foi commitado."),
    (4, "Deploy sem defaults de segredo",
     "deploy/compose.yaml:30 usa ${POSTGRES_PASSWORD:?...}; JWT, senha inicial, Nectar e Gmail "
     "vêm vazios em .env.example e deploy/.env.example."),
    (4, "Bundle do frontend limpo",
     "dist/assets/*.js não contém chave; a única variável VITE_ é VITE_API_URL (endereço, não "
     "segredo)."),
    (5, "Nenhum sink de XSS no frontend",
     "Zero ocorrências de dangerouslySetInnerHTML, innerHTML, insertAdjacentHTML, eval, "
     "new Function, href/src dinâmico ou lib de markdown em src/; tudo passa pelo escape do "
     "React. Dados vindos de e-mail e do CRM são exibidos como texto."),
    (5, "Backend não gera HTML",
     "Nenhum e-mail é enviado (Gmail é gmail.readonly) e as respostas são JSON/ProblemDetail; "
     "server.error.include-* = never em application-prod.properties."),
    (1, "Banco e API fora da internet",
     "deploy/compose.yaml: só o Caddy publica porta; Postgres com scram-sha-256; container não "
     "roda como root (Dockerfile); /actuator responde 404 de fora (Caddyfile:34-36); Swagger "
     "desligado em prod."),
]

FORA_DO_ESCOPO = [
    ("ControleDeTentativasDeLogin.java:100-104",
     "Usa o primeiro endereço de X-Forwarded-For. Seguro hoje porque o Caddy substitui o "
     "cabeçalho vindo de cliente não confiável. Ao ligar o Cloudflare (item 24), configurar "
     "trusted_proxies do Cloudflare no Caddy — senão o limite por origem passa a contar o IP do "
     "nó do Cloudflare."),
    ("scripts/obter-refresh-token-gmail.ps1:48-60",
     "Fluxo OAuth de loopback sem parâmetro state/PKCE. Uso único e local; risco baixo."),
    ("deploy/backup.sh:31-36",
     "O dump completo do banco é gravado com o umask padrão. Considerar umask 077 no script."),
]

RECOMENDACOES = [
    ("P1", "A-01", "Blindar a conta técnica de integração e auditar a gestão de usuários",
     "Antes da homologação com a equipe: é o único achado que corrompe a trilha de auditoria."),
    ("P2", "A-02", "Fail-fast de segredos no perfil prod e política na senha inicial do admin",
     "Antes do primeiro deploy (item 23 do roadmap)."),
    ("P2", "A-04", "Content-Security-Policy no Caddy (começar em Report-Only)",
     "Barato, e contém o dano do único cenário que exporia os tokens."),
    ("P3", "A-03", "Postgres de dev só no loopback e sem senha fixa commitada", "Higiene."),
    ("P3", "A-05", "Reavaliar a janela de 15 min de revogação",
     "Só se o desligamento imediato virar requisito."),
    ("P3", "—", "trusted_proxies do Cloudflare no Caddy ao ligar o WAF",
     "Junto com o item 24 do roadmap."),
]

ISSUES = [
    {
        "titulo": "[Segurança] GESTOR consegue reativar e assumir a conta técnica de integração",
        "labels": "security, severity:medium",
        "achados": ["A-01"],
        "corpo": """## Descrição
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
""",
    },
    {
        "titulo": "[Segurança] Validar segredos no boot e remover credenciais fixas de dev",
        "labels": "security, severity:low",
        "achados": ["A-02", "A-03"],
        "corpo": """## Descrição
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
""",
    },
    {
        "titulo": "[Segurança] Adicionar Content-Security-Policy e reduzir a exposição dos tokens",
        "labels": "security, severity:low",
        "achados": ["A-04"],
        "corpo": """## Descrição
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
""",
    },
    {
        "titulo": "[Segurança] Reavaliar a janela de 15 min para revogar acesso e papel",
        "labels": "security, severity:informational",
        "achados": ["A-05"],
        "corpo": """## Descrição
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
""",
    },
]

# --------------------------------------------------------------------------- fontes
FONTES = os.path.join(matplotlib.get_data_path(), "fonts", "ttf")
pdfmetrics.registerFont(TTFont("Sans", os.path.join(FONTES, "DejaVuSans.ttf")))
pdfmetrics.registerFont(TTFont("Sans-Bold", os.path.join(FONTES, "DejaVuSans-Bold.ttf")))
pdfmetrics.registerFont(TTFont("Sans-Italic", os.path.join(FONTES, "DejaVuSans-Oblique.ttf")))
pdfmetrics.registerFont(TTFont("Mono", os.path.join(FONTES, "DejaVuSansMono.ttf")))
pdfmetrics.registerFontFamily("Sans", normal="Sans", bold="Sans-Bold", italic="Sans-Italic",
                              boldItalic="Sans-Bold")
plt.rcParams["font.family"] = font_manager.FontProperties(
    fname=os.path.join(FONTES, "DejaVuSans.ttf")).get_name()

# --------------------------------------------------------------------------- estilos
def estilo(nome, **kw):
    base = dict(fontName="Sans", fontSize=9.5, leading=13.5, textColor=CINZA_TEXTO)
    base.update(kw)
    return ParagraphStyle(nome, **base)


S = {
    "corpo": estilo("corpo"),
    "pequeno": estilo("pequeno", fontSize=8.5, leading=11.5),
    "h1": estilo("h1", fontName="Sans-Bold", fontSize=17, leading=22, textColor=VERDE_ESCURO,
                 spaceBefore=4, spaceAfter=10),
    "h2": estilo("h2", fontName="Sans-Bold", fontSize=12.5, leading=16, textColor=VERDE_ESCURO,
                 spaceBefore=12, spaceAfter=6),
    "h3": estilo("h3", fontName="Sans-Bold", fontSize=10.5, leading=14, textColor=CINZA_TEXTO,
                 spaceBefore=8, spaceAfter=4),
    "celula": estilo("celula", fontSize=8.3, leading=11),
    "celula_b": estilo("celula_b", fontName="Sans-Bold", fontSize=8.3, leading=11),
    "capa_cel": estilo("capa_cel", fontSize=7.6, leading=9.8),
    "capa_cel_b": estilo("capa_cel_b", fontName="Sans-Bold", fontSize=7.6, leading=9.8),
    "cab": estilo("cab", fontName="Sans-Bold", fontSize=8.3, leading=11, textColor=colors.white),
    "mono": estilo("mono", fontName="Mono", fontSize=7.4, leading=9.6,
                   textColor=colors.HexColor("#1F2937")),
    "chip": estilo("chip", fontName="Sans-Bold", fontSize=6.9, leading=9,
                   textColor=colors.white, alignment=TA_CENTER),
}


def p(texto, st="corpo"):
    return Paragraph(texto, S[st])


def mono_linhas(texto):
    """Cada linha vira um Paragraph monoespaçado; indentação preservada com &nbsp;."""
    saida = []
    for linha in texto.split("\n"):
        recuo = len(linha) - len(linha.lstrip(" "))
        conteudo = "&nbsp;" * recuo + escape(linha.lstrip(" "))
        saida.append(Paragraph(conteudo or "&nbsp;", S["mono"]))
    return saida


def bloco_codigo(texto, largura, fundo="#F8FAF8", borda="#D1D5DB"):
    """Uma linha por linha de tabela: assim o bloco pode quebrar entre páginas."""
    linhas = [[par] for par in mono_linhas(texto)]
    t = Table(linhas, colWidths=[largura], splitByRow=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(fundo)),
        ("BOX", (0, 0), (-1, -1), 0.6, colors.HexColor(borda)),
        ("LEFTPADDING", (0, 0), (-1, -1), 7), ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, 0), 5), ("BOTTOMPADDING", (0, -1), (-1, -1), 5),
    ]))
    return t


def chip(sev, largura=2.3 * cm):
    t = Table([[Paragraph(ROTULO.get(sev, "Ponto forte").upper(), S["chip"])]],
              colWidths=[largura], rowHeights=[0.5 * cm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(COR[sev])),
        ("ROUNDEDCORNERS", [6, 6, 6, 6]),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 0), ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
        ("LEFTPADDING", (0, 0), (-1, -1), 2), ("RIGHTPADDING", (0, 0), (-1, -1), 2),
    ]))
    return t


def tabela(linhas, larguras, cabecalho=True, zebra=True):
    t = Table(linhas, colWidths=larguras, repeatRows=1 if cabecalho else 0)
    estilos = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.4, BORDA),
        ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5),
    ]
    if cabecalho:
        estilos += [("BACKGROUND", (0, 0), (-1, 0), VERDE_ESCURO)]
    if zebra:
        for i in range(1 if cabecalho else 0, len(linhas)):
            if i % 2 == 0:
                estilos.append(("BACKGROUND", (0, i), (-1, i), CINZA_CLARO))
    t.setStyle(TableStyle(estilos))
    return t


# --------------------------------------------------------------------------- gráficos
def contagem_por_sev():
    return {s: sum(1 for a in ACHADOS if a["sev"] == s) for s in ORDEM_SEV}


def grafico_rosca(caminho):
    cont = contagem_por_sev()
    presentes = [s for s in ORDEM_SEV if cont[s]]
    fig, ax = plt.subplots(figsize=(4.2, 3.4), dpi=200)
    ax.pie([cont[s] for s in presentes], colors=[COR[s] for s in presentes], startangle=90,
           counterclock=False, wedgeprops=dict(width=0.36, edgecolor="white", linewidth=2))
    ax.text(0, 0.08, str(len(ACHADOS)), ha="center", va="center", fontsize=26,
            color="#244F26", fontweight="bold")
    ax.text(0, -0.22, "achados", ha="center", va="center", fontsize=10, color="#424342")
    ax.set_aspect("equal")
    rotulos = [f"{ROTULO[s]}: {cont[s]}" for s in ORDEM_SEV]
    alcas = [plt.Rectangle((0, 0), 1, 1, color=COR[s]) for s in ORDEM_SEV]
    ax.legend(alcas, rotulos, loc="center left", bbox_to_anchor=(1.0, 0.5), frameon=False,
              fontsize=9)
    ax.set_title("Achados por severidade", fontsize=11, color="#244F26", pad=6)
    fig.savefig(caminho, bbox_inches="tight", transparent=False, facecolor="white")
    plt.close(fig)


def grafico_barras(caminho):
    nomes = [f"{n}. {t}" for n, t in CATEGORIAS]
    fig, ax = plt.subplots(figsize=(7.2, 3.3), dpi=200)
    y = list(range(len(CATEGORIAS)))[::-1]
    alt = 0.36
    esquerda = [0] * len(CATEGORIAS)
    for s in ORDEM_SEV:
        vals = [sum(1 for a in ACHADOS if a["cat"] == n and a["sev"] == s) for n, _ in CATEGORIAS]
        if any(vals):
            ax.barh([v + alt / 2 for v in y], vals, height=alt, left=esquerda, color=COR[s],
                    label=ROTULO[s])
            esquerda = [e + v for e, v in zip(esquerda, vals)]
    fortes = [sum(1 for f in PONTOS_FORTES if f[0] == n) for n, _ in CATEGORIAS]
    ax.barh([v - alt / 2 for v in y], fortes, height=alt, color=COR["forte"],
            label="Pontos fortes")
    for yi, total, forte in zip(y, esquerda, fortes):
        if total:
            ax.text(total + 0.08, yi + alt / 2, str(total), va="center", fontsize=8)
        ax.text(forte + 0.08, yi - alt / 2, str(forte), va="center", fontsize=8,
                color=COR["forte"])
    ax.set_yticks(y)
    ax.set_yticklabels(nomes, fontsize=8.5)
    ax.set_xlim(0, max(max(esquerda), max(fortes)) + 1)
    ax.xaxis.set_major_locator(matplotlib.ticker.MaxNLocator(integer=True))
    ax.tick_params(axis="x", labelsize=8)
    for lado in ("top", "right"):
        ax.spines[lado].set_visible(False)
    ax.grid(axis="x", color="#E5E7EB", linewidth=0.6)
    ax.set_axisbelow(True)
    ax.legend(fontsize=8, frameon=False, loc="lower right")
    ax.set_title("Achados e pontos fortes por categoria", fontsize=11, color="#244F26")
    fig.savefig(caminho, bbox_inches="tight", facecolor="white")
    plt.close(fig)


# --------------------------------------------------------------------------- páginas
MARGEM = 2 * cm
LARGURA_UTIL = A4[0] - 2 * MARGEM


def cabecalho_rodape(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(BORDA)
    canvas.setLineWidth(0.6)
    canvas.line(MARGEM, A4[1] - 1.35 * cm, A4[0] - MARGEM, A4[1] - 1.35 * cm)
    canvas.line(MARGEM, 1.35 * cm, A4[0] - MARGEM, 1.35 * cm)
    canvas.setFont("Sans", 7.8)
    canvas.setFillColor(CINZA_TEXTO)
    canvas.drawString(MARGEM, A4[1] - 1.15 * cm, TITULO)
    canvas.drawRightString(A4[0] - MARGEM, A4[1] - 1.15 * cm, DATA)
    canvas.drawString(MARGEM, 0.95 * cm, "Confidencial — uso interno ConectSol")
    canvas.drawRightString(A4[0] - MARGEM, 0.95 * cm, f"Página {doc.page}")
    canvas.restoreState()


def capa_fundo(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(VERDE_ESCURO)
    canvas.rect(0, A4[1] - 9.5 * cm, A4[0], 9.5 * cm, stroke=0, fill=1)
    canvas.setFillColor(colors.HexColor("#1EFC1E"))
    canvas.rect(MARGEM, A4[1] - 9.5 * cm - 0.12 * cm, 3 * cm, 0.24 * cm, stroke=0, fill=1)
    canvas.setFont("Sans", 7.8)
    canvas.setFillColor(CINZA_TEXTO)
    canvas.drawString(MARGEM, 0.95 * cm, "Confidencial — uso interno ConectSol")
    canvas.drawRightString(A4[0] - MARGEM, 0.95 * cm, f"Página {doc.page}")
    canvas.restoreState()


def montar_capa():
    branco = ParagraphStyle("capa_t", fontName="Sans-Bold", fontSize=25, leading=31,
                            textColor=colors.white)
    sub = ParagraphStyle("capa_s", fontName="Sans", fontSize=11.5, leading=16,
                         textColor=colors.HexColor("#D1FAD1"))
    hist = [
        Spacer(1, 1.2 * cm),
        Paragraph("Relatório de Auditoria de Segurança", branco),
        Paragraph(f"— {PROJETO}", branco),
        Spacer(1, 0.4 * cm),
        Paragraph(f"ConectSol · {DATA} · cinco categorias: isolamento, permissão no "
                  "navegador, IDOR, chaves expostas e XSS", sub),
        Spacer(1, 2.3 * cm),
        p("Escopo auditado", "h2"),
        tabela([
            [p("Repositório", "cab"), p("Conteúdo", "cab")],
            [p("solarsync (branch Plan, c4f8a66)", "celula_b"),
             p("API Spring Boot: 10 controllers / 70 handlers, services, 18 migrations, "
               "application*.properties, compose.yaml, Dockerfile, deploy/ (compose, Caddyfile, "
               "scripts), scripts/, docs/, testes e histórico git (24 commits)", "celula")],
            [p("solarsync-front (branch solarsync-homologacao-projetos, 2da1971)", "celula_b"),
             p("Interface React: src/ (54 arquivos), index.html, Dockerfile, dist/ compilado "
               "e histórico git (13 commits)", "celula")],
        ], [5.2 * cm, LARGURA_UTIL - 5.2 * cm]),
        Spacer(1, 0.3 * cm),
        p("Nota metodológica", "h2"),
    ]
    linhas = [[p("Categoria", "cab"), p("Como foi mapeada para a stack", "cab")]]
    for nome, texto in METODOLOGIA:
        linhas.append([p(nome, "capa_cel_b"), p(texto, "capa_cel")])
    hist.append(tabela(linhas, [3.2 * cm, LARGURA_UTIL - 3.2 * cm]))
    hist.append(Spacer(1, 0.2 * cm))
    hist.append(p("Só entram achados verificados no código. Categorias que não se aplicam à "
                  "stack são declaradas como tal, com a evidência de por quê.", "pequeno"))
    return hist


def montar_resumo(tmp):
    rosca, barras = os.path.join(tmp, "rosca.png"), os.path.join(tmp, "barras.png")
    grafico_rosca(rosca)
    grafico_barras(barras)
    cont = contagem_por_sev()

    cartoes = []
    for s in ORDEM_SEV:
        c = Table([[Paragraph(f"<font size=20><b>{cont[s]}</b></font>",
                              ParagraphStyle("n", fontName="Sans-Bold", alignment=TA_CENTER,
                                             textColor=colors.HexColor(COR[s]), leading=24))],
                   [Paragraph(ROTULO[s], ParagraphStyle("r", fontName="Sans", fontSize=8.5,
                                                        alignment=TA_CENTER,
                                                        textColor=CINZA_TEXTO))]],
                  colWidths=[LARGURA_UTIL / 6 - 4])
        c.setStyle(TableStyle([
            ("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor(COR[s])),
            ("ROUNDEDCORNERS", [8, 8, 8, 8]),
            ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]))
        cartoes.append(c)
    fc = Table([[Paragraph(f"<font size=20><b>{len(PONTOS_FORTES)}</b></font>",
                           ParagraphStyle("n2", fontName="Sans-Bold", alignment=TA_CENTER,
                                          textColor=colors.HexColor(COR["forte"]), leading=24))],
                [Paragraph("Pontos fortes", ParagraphStyle("r2", fontName="Sans", fontSize=8.5,
                                                           alignment=TA_CENTER,
                                                           textColor=CINZA_TEXTO))]],
               colWidths=[LARGURA_UTIL / 6 - 4])
    fc.setStyle(TableStyle([("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor(COR["forte"])),
                            ("ROUNDEDCORNERS", [8, 8, 8, 8]),
                            ("TOPPADDING", (0, 0), (-1, -1), 4),
                            ("BOTTOMPADDING", (0, 0), (-1, -1), 4)]))
    cartoes.append(fc)
    faixa = Table([cartoes], colWidths=[LARGURA_UTIL / 6] * 6)
    faixa.setStyle(TableStyle([("LEFTPADDING", (0, 0), (-1, -1), 2),
                               ("RIGHTPADDING", (0, 0), (-1, -1), 2)]))

    hist = [
        p("Resumo executivo", "h1"),
        p(f"A auditoria encontrou <b>{len(ACHADOS)} achados</b>: nenhum crítico ou alto, "
          f"{cont['media']} de severidade média, {cont['baixa']} baixos e {cont['informativa']} "
          "informativo. O controle de acesso é feito no servidor em todos os 70 handlers, não "
          "há segredo real nem no código nem no histórico git, e o frontend não tem nenhum sink "
          "de XSS. As categorias 1 (isolamento por dono) e 3 (IDOR) não se aplicam no sentido "
          "clássico: o sistema é single-tenant e todo papel lê tudo por decisão de negócio "
          "documentada. O único achado com impacto real é a conta técnica de integração, que "
          "um GESTOR consegue reativar e usar para gravar ações em nome da automação."),
        Spacer(1, 0.35 * cm),
        faixa,
        Spacer(1, 0.4 * cm),
        Image(rosca, width=11 * cm, height=11 * cm * 3.4 / 5.6, kind="proportional"),
        Spacer(1, 0.2 * cm),
        Image(barras, width=LARGURA_UTIL, height=LARGURA_UTIL * 3.3 / 7.2, kind="proportional"),
        PageBreak(),
    ]
    return hist


def montar_pontos():
    hist = [p("Pontos fortes e pontos fracos", "h1"), p("Pontos fortes (verificados)", "h2")]
    nomes_cat = dict(CATEGORIAS)
    linhas = [[p("Cat.", "cab"), p("O que está protegido", "cab"), p("Evidência", "cab")]]
    for cat, titulo, evid in PONTOS_FORTES:
        linhas.append([p(f"<font color='{COR['forte']}'><b>{cat}</b></font>", "celula"),
                       p(f"<font color='{COR['forte']}'>●</font> <b>{escape(titulo)}</b>",
                         "celula"),
                       p(escape(evid), "celula")])
    hist.append(tabela(linhas, [1.1 * cm, 5.0 * cm, LARGURA_UTIL - 6.1 * cm]))
    hist.append(p("Categorias sem achado", "h2"))
    for n in (1, 3):
        hist.append(p(f"<b>{n}. {nomes_cat[n]} — não se aplica.</b> " + (
            "Não há multi-tenancy nem posse de registro por usuário: é uma empresa só, e "
            "ANALISTA vê todos os clientes por decisão documentada (CLAUDE.md §4, §8 itens 4 e "
            "5). Não existe filtro por dono a faltar. O que protege os dados é a autenticação "
            "obrigatória e o banco fora da internet."
            if n == 1 else
            "Os 70 handlers buscam por id sem checar dono, e isso é o comportamento "
            "especificado (qualquer papel lê e edita qualquer etapa; só ADMINISTRADOR apaga). "
            "Onde há hierarquia de objeto — usuários — ela é verificada no servidor, com a "
            "exceção reportada em A-01. O autor gravado na auditoria nunca vem do corpo da "
            "requisição.")))
        hist.append(Spacer(1, 0.15 * cm))
    hist.append(p("Pontos fracos (riscos centrais)", "h2"))
    for a in ACHADOS:
        hist.append(Table([[chip(a["sev"]), p(f"<b>{a['id']} · {escape(a['titulo'])}</b> — "
                                              f"{escape(a['explorabilidade'])}", "celula")]],
                          colWidths=[2.6 * cm, LARGURA_UTIL - 2.6 * cm],
                          style=[("VALIGN", (0, 0), (-1, -1), "TOP"),
                                 ("BOTTOMPADDING", (0, 0), (-1, -1), 6)]))
    hist.append(p("Observações fora das cinco categorias", "h2"))
    for loc, txt in FORA_DO_ESCOPO:
        hist.append(p(f"<font name='Mono' size=8>{escape(loc)}</font> — {escape(txt)}",
                      "pequeno"))
        hist.append(Spacer(1, 0.1 * cm))
    hist.append(PageBreak())
    return hist


def montar_achados():
    hist = [p("Achados detalhados por categoria", "h1"),
            p("Nos caminhos abaixo, <font name='Mono' size=8>…/</font> abrevia "
              f"<font name='Mono' size=8>{PACOTE}</font>. Os caminhos "
              "<font name='Mono' size=8>solarsync-front/</font> são do repositório do frontend.",
              "pequeno")]
    for n, nome in CATEGORIAS:
        doCat = [a for a in ACHADOS if a["cat"] == n]
        hist.append(p(f"{n}. {nome}", "h2"))
        if not doCat:
            hist.append(p("Nenhum achado — ver \"Categorias sem achado\" e os pontos fortes."
                          if n in (1, 3) else "Nenhum achado."))
            continue
        linhas = [[p("Severidade", "cab"), p("Arquivo:linha", "cab"), p("Descrição", "cab")]]
        for a in doCat:
            locais = "<br/>".join(
                f"<font name='Mono' size=7.2>{escape(l.replace(PACOTE, '…/'))}</font>"
                for l in a["local"])
            linhas.append([chip(a["sev"]), p(locais, "celula"),
                           p(f"<b>{a['id']} · {escape(a['titulo'])}.</b> "
                             f"{escape(a['descricao'])}", "celula")])
        hist.append(tabela(linhas, [2.6 * cm, 5.6 * cm, LARGURA_UTIL - 8.2 * cm], zebra=False))
        for a in doCat:
            hist.append(KeepTogether([
                p(f"{a['id']} — trecho, explorabilidade e correção", "h3"),
                bloco_codigo(a["trecho"], LARGURA_UTIL),
            ]))
            hist.append(Spacer(1, 0.15 * cm))
            hist.append(p(f"<b>Explorabilidade:</b> {escape(a['explorabilidade'])}"))
            hist.append(Spacer(1, 0.1 * cm))
            hist.append(p(f"<b>Correção:</b> {escape(a['correcao'])}"))
    hist.append(PageBreak())
    return hist


def montar_recomendacoes():
    cor_p = {"P1": COR["critica"], "P2": COR["alta"], "P3": COR["baixa"]}
    linhas = [[p("Prioridade", "cab"), p("Achado", "cab"), p("Ação", "cab"),
               p("Quando / por quê", "cab")]]
    for pr, ach, acao, quando in RECOMENDACOES:
        linhas.append([p(f"<font color='{cor_p[pr]}'><b>{pr}</b></font>", "celula"),
                       p(ach, "celula_b"), p(escape(acao), "celula"),
                       p(escape(quando), "celula")])
    return [p("Recomendações priorizadas", "h1"),
            tabela(linhas, [2.4 * cm, 1.6 * cm, 6.9 * cm, LARGURA_UTIL - 10.9 * cm]),
            PageBreak()]


def texto_issue(i, issue):
    return (f"--- ISSUE {i} ---\n"
            f"Título: {issue['titulo']}\n"
            f"Labels: {issue['labels']}\n"
            f"Achados: {', '.join(issue['achados'])}\n\n"
            f"{issue['corpo'].strip()}\n"
            f"--- FIM ISSUE {i} ---")


def montar_issues():
    hist = [p("ISSUES PARA O GITHUB", "h1"),
            p("Texto completo de cada issue em Markdown, pronto para copiar e colar. Os achados "
              "A-02 e A-03 foram agrupados numa issue só (mesmo tema: segredos e defaults). O "
              "mesmo conteúdo está em docs/security-audit/issues-github.md."),
            Spacer(1, 0.3 * cm)]
    for i, issue in enumerate(ISSUES, 1):
        hist.append(p(f"Issue {i} — {escape(issue['titulo'])}", "h3"))
        hist.append(bloco_codigo(texto_issue(i, issue), LARGURA_UTIL, fundo="#FBFBFB",
                                 borda="#9CA3AF"))
        hist.append(Spacer(1, 0.4 * cm))
    return hist


def main():
    with tempfile.TemporaryDirectory() as tmp:
        doc = BaseDocTemplate(SAIDA_PDF, pagesize=A4, leftMargin=MARGEM, rightMargin=MARGEM,
                              topMargin=MARGEM, bottomMargin=MARGEM, title=TITULO,
                              author="Auditoria de segurança — ConectSol",
                              subject="Auditoria das cinco categorias")
        quadro = Frame(MARGEM, MARGEM, LARGURA_UTIL, A4[1] - 2 * MARGEM, id="normal")
        quadro_capa = Frame(MARGEM, MARGEM, LARGURA_UTIL, A4[1] - MARGEM - 1.2 * cm,
                            id="capa")
        doc.addPageTemplates([
            PageTemplate(id="Capa", frames=[quadro_capa], onPage=capa_fundo),
            PageTemplate(id="Normal", frames=[quadro], onPage=cabecalho_rodape),
        ])
        hist = montar_capa() + [NextPageTemplate("Normal"), PageBreak()]
        hist += montar_resumo(tmp) + montar_pontos() + montar_achados()
        hist += montar_recomendacoes() + montar_issues()
        doc.build(hist)

    with open(SAIDA_MD, "w", encoding="utf-8") as f:
        f.write(f"# Issues para o GitHub — {TITULO}\n\n")
        f.write("\n\n".join(texto_issue(i, iss) for i, iss in enumerate(ISSUES, 1)))
        f.write("\n")
    print(SAIDA_PDF)
    print(SAIDA_MD)


if __name__ == "__main__":
    main()
