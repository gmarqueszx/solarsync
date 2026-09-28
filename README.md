# SolarSync

Sistema web da **ConectSol** que substitui a planilha Excel usada hoje para controlar a
homologação de projetos solares na Coelba/Neoenergia. Acompanha cada cliente desde a entrada
(validado pelo financeiro) até a vistoria aprovada, com dashboard de tempo de ciclo para o gestor.

Este repositório é a **API** (Java/Spring Boot + PostgreSQL) e também guarda a **configuração do
deploy da pilha inteira**. A interface fica em outro repositório:

| Repositório | O que é | Pasta local esperada |
|---|---|---|
| [`gmarqueszx/solarsync`](https://github.com/gmarqueszx/solarsync) | API, banco (migrations) e deploy | `solarsync/` |
| [`gmarqueszx/solarsync-web`](https://github.com/gmarqueszx/solarsync-web) | Interface React + TypeScript + Vite | `solarsync-front/` ⚠️ o nome da pasta é diferente do repositório |

Os dois são **privados**: quem vai fazer o deploy precisa ser adicionado como colaborador nos dois.

---

## Sumário

1. [O que o sistema faz](#1-o-que-o-sistema-faz)
2. [Arquitetura](#2-arquitetura)
3. [Rodando na sua máquina](#3-rodando-na-sua-máquina)
4. [Testes](#4-testes)
5. [Deploy — resumo para quem vai publicar](#5-deploy--resumo-para-quem-vai-publicar)
6. [Variáveis de ambiente](#6-variáveis-de-ambiente)
7. [Integrações externas (e por que ficam desligadas)](#7-integrações-externas-e-por-que-ficam-desligadas)
8. [Problemas conhecidos e como diagnosticar](#8-problemas-conhecidos-e-como-diagnosticar)
9. [Onde está o resto da documentação](#9-onde-está-o-resto-da-documentação)

---

## 1. O que o sistema faz

O fluxo tem quatro etapas, e cada módulo do sistema corresponde a uma delas:

| Etapa | Módulos | Resumo |
|---|---|---|
| 1. Entrada e pendências | Clientes, Pendências | O cliente entra na fila de triagem; alguém verifica se há pendência na Coelba (troca de titularidade, ligação nova…). Sem pendência, segue direto |
| 2. Débito e homologação | Débitos, Projetos | Consulta de débito na agência virtual. Sem débito, o projeto é enviado à Coelba com ART |
| 3. Acompanhamento | Projetos | A Coelba responde (aprovado / reprovado / em análise). Reprovado volta para correção |
| 4. Vistoria e unificação | Vistoria, Unificação | Vistoria pós-instalação; unificação de medidores e desligamento |

Pontos que vale saber antes de mexer em qualquer coisa:

- **A mudança de status de uma etapa dispara a próxima sozinha.** Ex.: resolver a pendência cria
  o projeto automaticamente. Isso é feito por eventos de domínio, não no controller.
- **Todo status passa pelo service do módulo**, que é o único lugar que publica o evento. Nunca
  altere status com `repository.save()` direto nem por SQL — a auditoria e a automação deixam de
  funcionar.
- **`historico_status` é a auditoria** e é de onde saem as métricas do dashboard. Não tem endpoint
  de escrita, de propósito.
- **Papéis**: `ADMINISTRADOR` (acesso total), `GESTOR` (operação + cadastro de usuários) e
  `ANALISTA` (trabalha em todas as etapas, mas não apaga nada).

## 2. Arquitetura

```
  navegador ── https://solarsync.conectsol.com ──► VPS (Docker Compose)
                                                    ├── caddy     :80 :443   TLS automático (Let's Encrypt)
                                                    │     ├── /        interface (estáticos do solarsync-web)
                                                    │     └── /api/*   proxy para a API
                                                    ├── api       :8080  rede interna
                                                    └── postgres  :5432  rede interna
```

- **Um domínio só**: tela em `/` e API em `/api/*`. Não há CORS para configurar.
- **Só o Caddy publica porta.** Postgres e API ficam na rede interna do compose.
- **Um VPS, e não Cloud Run/serverless**: há jobs agendados (Nectar a cada 10 min, Gmail a cada
  15, reprocessamento a cada 30) que não rodariam com escala a zero — e falhariam em silêncio.

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1 (WebMVC, Security, Data JPA, Validation), Maven |
| Banco | PostgreSQL 16, migrations Flyway em `src/main/resources/db/migration` (V1…V19) |
| Autenticação | JWT HS256 próprio (access 15 min, refresh 8 h), login por e-mail e senha |
| API | springdoc-openapi — contrato em `docs/api/openapi.json` |
| Testes | JUnit 5, AssertJ, Mockito, **Testcontainers com Postgres real** |
| Deploy | Docker Compose + Caddy |

O schema é **sempre** da migration: o Hibernate roda com `ddl-auto=validate`, então entidade e
migration divergentes derrubam o boot de propósito.

Estrutura de pacotes (`com.conectsol.solarsync`): um pacote por etapa (`cliente`, `pendencia`,
`debito`, `projeto`, `vistoria`, `unificacao`), mais `auth`, `historico`, `dashboard`,
`integracao` (jobs do Nectar e do Gmail) e `common`.

## 3. Rodando na sua máquina

Pré-requisitos: **JDK 21** e **Docker** (Docker Desktop no Windows). O Maven vem pelo wrapper
(`./mvnw`), não precisa instalar.

### 3.1. Configuração local

Crie `config/application.properties` na raiz do repositório (a pasta `config/` está no
`.gitignore`). O Spring Boot lê esse arquivo sozinho, sem profile nem variável de ambiente:

```properties
solarsync.jwt.segredo=uma-string-qualquer-com-pelo-menos-32-caracteres
solarsync.admin.senha-inicial=uma-senha-com-12-ou-mais
solarsync.dados-de-exemplo=true
```

- `senha-inicial` é aplicada **uma vez** ao administrador semeado (`joaogabriel@conectsol.com`).
  Não há auto-cadastro: sem ela ninguém entra num banco novo.
- `dados-de-exemplo=true` semeia 26 clientes cobrindo todos os estados do fluxo.
- ⚠️ **Não** ponha `solarsync.*.ativo=true` (integrações) nesse arquivo — ele também é lido pelos
  testes, e os jobs passariam a chamar o CRM e o Gmail reais. Ver seção 7.

A referência completa dos nomes está em `.env.example`.

### 3.2. Subir a API

```sh
./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```

O `compose.yaml` da raiz é usado automaticamente pelo suporte a Docker Compose do Spring Boot:
ele sobe um Postgres 16 local (só no loopback) e conecta a API nele. O Flyway cria o schema.

- API: <http://localhost:8080>
- Swagger: <http://localhost:8080/swagger-ui.html> (desligado no perfil `prod`)
- Zerar o banco de dev: `docker compose down -v`

### 3.3. Subir a interface

No clone do `solarsync-web` (pasta `solarsync-front`), `npm ci && npm run dev` — abre em
<http://localhost:5173> apontando para a API local. Detalhes no README/CLAUDE.md de lá.

## 4. Testes

```sh
./mvnw test
```

**Exige Docker rodando**: os testes sobem um Postgres de verdade via Testcontainers (não H2,
porque o schema usa recursos específicos do Postgres).

⚠️ A imagem de produção **não roda os testes** (seria Docker dentro de Docker). Rodar
`./mvnw test` localmente **antes de publicar** é responsabilidade de quem faz o deploy.

⚠️ Os jobs de integração não são exercitados pelos testes (ficam desligados). Depois de mexer em
`integracao/`, a única prova é subir a aplicação com a integração ligada.

## 5. Deploy — resumo para quem vai publicar

> O passo a passo completo, com os porquês, está em **[`deploy/README.md`](deploy/README.md)**.
> Esta seção é o mapa; leia o runbook antes da primeira vez.

### 5.1. O que precisa existir antes (não é código)

- [ ] **VPS** Ubuntu 24.04, mínimo **2 vCPU / 4 GB RAM / 40 GB**, de preferência em São Paulo
      (cada clique vai ao servidor; Europa = ~200 ms a mais por ação)
- [ ] **Registro DNS** `A` `solarsync.conectsol.com` → IP do VPS, com o proxy do Cloudflare
      **desligado (nuvem cinza)** até o primeiro certificado sair
- [ ] Acesso de leitura aos **dois** repositórios a partir do servidor (deploy key por repositório
      é o caminho recomendado)
- [ ] Docker no servidor: `curl -fsSL https://get.docker.com | sh`

### 5.2. Primeira vez, em cinco comandos

```sh
# 1. firewall: só SSH, HTTP e HTTPS
ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp && ufw enable

# 2. os dois repositórios LADO A LADO, e a pasta do front com este nome exato
mkdir -p /opt/solarsync && cd /opt/solarsync
git clone -b main git@github.com:gmarqueszx/solarsync.git
git clone -b main git@github.com:gmarqueszx/solarsync-web.git solarsync-front

# 3. segredos
cd solarsync/deploy && cp .env.example .env
openssl rand -base64 32   # → POSTGRES_PASSWORD
openssl rand -base64 48   # → SOLARSYNC_JWT_SEGREDO
nano .env                 # + SOLARSYNC_ADMIN_SENHA_INICIAL (12+ caracteres) e SOLARSYNC_DOMINIO

# 4. publicar
chmod +x deploy.sh backup.sh && ./deploy.sh

# 5. backup diário (crontab -e)
15 3 * * * /opt/solarsync/solarsync/deploy/backup.sh >> /opt/solarsync/backup.log 2>&1
```

O `deploy.sh` atualiza **os dois** repositórios, constrói as imagens, sobe a pilha e espera o
healthcheck. Se a API não subir, ele mostra o log e sai com erro.

### 5.3. Conferir que está no ar

```sh
curl -sI https://solarsync.conectsol.com/                 # 200, certificado válido
curl -sI https://solarsync.conectsol.com/api/auth/eu      # 401 — a API responde
curl -s  https://solarsync.conectsol.com/actuator/health  # 404 — bloqueado de fora, correto
```

No navegador: entrar com `joaogabriel@conectsol.com` e a senha inicial, e ver os clientes de
exemplo no Dashboard. Na aba **Rede** do navegador, as chamadas devem ir para `/api/...` no mesmo
host, **sem** requisição `OPTIONS` (se aparecer, alguém embutiu `VITE_API_URL` no build do front).

Depois do primeiro login, **troque a senha do administrador pela tela** (Usuários & Acesso) e
cadastre os demais usuários por lá. Mudar `SOLARSYNC_ADMIN_SENHA_INICIAL` depois não tem efeito.

### 5.4. Deploys seguintes

```sh
./mvnw test                                           # na SUA máquina, antes
cd /opt/solarsync/solarsync/deploy && ./deploy.sh     # no servidor
```

A branch de deploy é a **`main`** nos dois repositórios. O trabalho entra por PR.

### 5.5. Regras que não podem ser quebradas

| Regra | O que acontece se quebrar |
|---|---|
| Não publicar a porta do Postgres nem da API | Banco exposto na internet |
| Não definir `SOLARSYNC_LOGIN_CONFIAR_EM_PROXY` no `.env` (o compose já força `true`) | O limite de tentativas de login vira global e tranca a empresa inteira |
| Não ligar a nuvem laranja do Cloudflare antes do primeiro certificado | O Let's Encrypt não consegue emitir e o HTTPS não sobe |
| Ao ligar a nuvem laranja, liberar a 443 só para [os IPs do Cloudflare](https://www.cloudflare.com/ips/) | Quem souber o IP contorna o WAF e forja `X-Forwarded-For` |
| Integrações desligadas em homologação | O CRM e a caixa de e-mail **reais** passam a ser lidos/escritos a partir de um ambiente de teste |
| `SOLARSYNC_DADOS_DE_EXEMPLO=false` em produção | Clientes fictícios misturados aos reais |
| Copiar os backups para fora do VPS depois que a planilha for importada | Perder o servidor = perder os dados |

### 5.6. Homologação × produção

É a mesma pilha, clonada em outra pasta com outro `.env`:

| Variável | Homologação | Produção |
|---|---|---|
| `SOLARSYNC_AMBIENTE` | `homologacao` | `producao` (separa containers e volumes) |
| `SOLARSYNC_DOMINIO` | `solarsync.conectsol.com` | o domínio definitivo |
| `SOLARSYNC_DADOS_DE_EXEMPLO` | `true` | `false` |
| `SOLARSYNC_NECTAR_ATIVO` | `false` | `true` |
| `SOLARSYNC_GMAIL_ATIVO` | `false` | `true`, começando com `SOMENTE_CONFERENCIA=true` |
| `SOLARSYNC_NECTAR_SAIDA_ATIVO` | `false` | `true`, começando com `SOMENTE_CONFERENCIA=true` |

## 6. Variáveis de ambiente

No servidor, o arquivo é **`deploy/.env`** (modelo em `deploy/.env.example`). O `.env.example` da
raiz explica o que cada variável da aplicação faz. Nenhum segredo vai para o Git.

| Variável | Obrigatória | Observação |
|---|---|---|
| `POSTGRES_PASSWORD` | sim | `openssl rand -base64 32` |
| `SOLARSYNC_JWT_SEGREDO` | sim | `openssl rand -base64 48`. Sem ela a API **não sobe** no perfil `prod` |
| `SOLARSYNC_ADMIN_SENHA_INICIAL` | no primeiro deploy | 12+ caracteres, senão a API não sobe. Aplicada uma única vez |
| `SOLARSYNC_DOMINIO` | sim | domínio único da tela e da API |
| `SOLARSYNC_ACME_EMAIL` | sim | recebe avisos do Let's Encrypt |
| `SOLARSYNC_FRONT_CAMINHO` | não | padrão `../../solarsync-front` |
| `SOLARSYNC_VERSAO` | não | etiqueta das imagens — use o hash do commit |
| `SOLARSYNC_API_MEMORIA` | não | teto de memória da API (padrão `1536m`). Não remova o teto |
| `SOLARSYNC_DADOS_DE_EXEMPLO` | não | `true` só em homologação |
| `SOLARSYNC_NECTAR_*`, `SOLARSYNC_GMAIL_*` | não | integrações — ver seção 7 |

## 7. Integrações externas (e por que ficam desligadas)

| Integração | Direção | Variável que liga |
|---|---|---|
| Nectar (CRM): cria o cliente quando o negócio fecha | entrada | `SOLARSYNC_NECTAR_ATIVO` + `_TOKEN` |
| Gmail: lê o retorno da Coelba e aprova/reprova o projeto | entrada | `SOLARSYNC_GMAIL_ATIVO` + `_CLIENT_ID`, `_CLIENT_SECRET`, `_REFRESH_TOKEN` |
| Nectar: move a etapa do cliente no CRM conforme o projeto anda | **saída (escreve no CRM)** | `SOLARSYNC_NECTAR_SAIDA_ATIVO` |

- Todas vêm **desligadas**, e sem a variável o job nem existe.
- Cada uma tem um **modo conferência** (`*_SOMENTE_CONFERENCIA=true`): faz tudo, grava o que
  *teria* feito numa tabela de trilha (`email_coelba`, `nectar_etapa_sincronizacao`) e não altera
  nada. Ao ligar em produção, comece sempre por ele e confira a trilha no banco antes de desligar.
- Integração mal configurada **não derruba** a API: loga ERROR e falha a cada execução.
- Ligar uma integração **não é tarefa do deploy de homologação**. Combine com o João Gabriel antes.

## 8. Problemas conhecidos e como diagnosticar

| Sintoma | Causa provável |
|---|---|
| `unable to prepare context: path ... not found` no build | O clone do front não está em `SOLARSYNC_FRONT_CAMINHO` — confira a estrutura de pastas da seção 5.2 |
| API não fica `healthy` | `./deploy.sh` já mostra o log; na maioria das vezes é `SOLARSYNC_JWT_SEGREDO` ausente ou senha inicial com menos de 12 caracteres |
| Certificado não é emitido | DNS ainda não propagou, porta 80 fechada ou nuvem laranja ligada no Cloudflare |
| Login com a senha inicial dá 401 | O admin já tinha senha; a variável só vale num banco novo. Troque por `POST /api/usuarios/{id}/senha` |
| Login dá 429 | Limite de tentativas (10 por e-mail / 60 por IP em 15 min). Espere o tempo indicado |
| Tela carrega mas nada funciona | Veja o console do navegador — normalmente CSP (origem externa nova) ou `VITE_API_URL` embutido |
| Postgres morre por falta de memória | Teto da API removido ou alto demais para o servidor |

Comandos úteis no servidor, de dentro de `deploy/`:

```sh
docker compose ps
docker compose logs -f --tail 200 api
docker compose exec postgres psql -U solarsync solarsync
docker compose restart api
```

## 9. Onde está o resto da documentação

| Arquivo | Conteúdo |
|---|---|
| [`deploy/README.md`](deploy/README.md) | **Runbook de deploy** passo a passo |
| [`CLAUDE.md`](CLAUDE.md) | Fonte de verdade do projeto: regras de negócio, decisões e o porquê de cada uma, RBAC, dashboard, integrações, buracos conhecidos |
| [`.env.example`](.env.example) | Explicação de cada variável da aplicação |
| [`deploy/.env.example`](deploy/.env.example) | Lista de variáveis do deploy |
| [`docs/api/openapi.json`](docs/api/openapi.json) | Contrato da API |
| [`docs/importacao/`](docs/importacao/LEIA-ME.md) | Planilhas-modelo para importar os dados da planilha antiga |
| [`docs/security-audit/`](docs/security-audit/) | Relatório da auditoria de segurança e os achados |

Dúvidas sobre regra de negócio ou sobre ligar integrações: **João Gabriel**
(joaogabriel@conectsol.com).
