# Deploy do SolarSync

Runbook do ambiente de **homologação** — o que a equipe de Projetos usa para testar antes de a
planilha ser substituída de verdade. Produção é a mesma pilha com outro `.env`; a seção final
diz o que muda.

## O desenho

```
  navegador
      │
      ├── https://solarsync.conectsol.com ──────► Cloudflare Pages (arquivos estáticos)
      │                                            build do repositório solarsync-web
      │
      └── https://api.solarsync.conectsol.com ──► VPS
                                                   │
                                                   ├── caddy    :80 :443  (TLS, único exposto)
                                                   ├── api      :8080     (rede interna)
                                                   └── postgres :5432     (rede interna)
```

**O frontend não fica no VPS.** Ele é um punhado de arquivos estáticos (`vite build`), e o
Cloudflare Pages os serve com TLS, CDN e build automático a cada push — de graça e sem nada para
manter. Isso deixa o servidor com uma responsabilidade só, e deixa o item 11 do checklist (WAF /
Cloudflare) meio caminho andado.

**Por que um VPS e não Cloud Run**, apesar de o outro projeto da casa usar Cloud Run: o SolarSync
tem trabalho agendado — o job do Nectar a cada 10 min, o do Gmail a cada 15, o reprocessamento da
saída a cada 30. Em Cloud Run com escala a zero, esses jobs simplesmente **não rodam** quando não
há requisição, e não rodam *em silêncio*: nenhum erro, nenhum log, os clientes só param de
entrar. Manter uma instância sempre ligada para evitar isso custa mais que o VPS e ainda exige
banco gerenciado à parte. Num servidor só, tudo funciona igual ao que já roda na sua máquina.

## O que você precisa ter em mãos

1. **Um VPS** com Ubuntu 24.04, mínimo **2 vCPU / 4 GB RAM / 40 GB** — a JVM com o Postgres ao
   lado não cabe confortavelmente em 2 GB, e o build do Maven dentro do servidor pede folga.
   Contabo (o do CLAUDE.md) ou Hetzner servem; qualquer um com IP público serve.
2. **Acesso ao DNS de `conectsol.com`** para criar dois registros.
3. `docker` e `docker compose` no servidor. No Ubuntu:
   `curl -fsSL https://get.docker.com | sh`

## Primeira vez

### 1. DNS

Dois registros `A` apontando para o IP do VPS:

| Nome | Tipo | Valor | Proxy Cloudflare |
|---|---|---|---|
| `api.solarsync` | A | IP do VPS | **DNS only** (nuvem cinza) |

E `solarsync.conectsol.com` como `CNAME` para o domínio que o Cloudflare Pages devolver (passo 5).

> ⚠️ A nuvem **cinza** no `api` não é detalhe: com o proxy ligado antes do primeiro certificado,
> o desafio do Let's Encrypt não chega até o Caddy e a emissão falha. Ligue o proxy depois, se
> quiser o WAF do item 11.

### 2. Firewall

Só 22, 80 e 443 entram. O Postgres **não** tem porta publicada no compose, mas um firewall
fechado é a segunda camada:

```sh
ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp && ufw enable
```

### 3. O código no servidor

A `main` é a branch de deploy: é ela que o servidor clona e é ela que o `deploy.sh` atualiza.
O trabalho entra por PR da branch de desenvolvimento, como os PRs #1 e #2.

```sh
mkdir -p /opt/solarsync && cd /opt/solarsync
git clone -b main https://github.com/gmarqueszx/solarsync.git
cd solarsync/deploy
```

> O repositório é privado, então o `clone` pede autenticação. O caminho com menos manutenção é
> uma **deploy key** somente-leitura: `ssh-keygen -t ed25519 -f ~/.ssh/solarsync -N ""` no
> servidor, a chave pública colada em Settings → Deploy keys do repositório, e o clone por
> `git@github.com:gmarqueszx/solarsync.git`. Um token pessoal também funciona, mas vence e leva
> junto o acesso a todos os seus outros repositórios.

### 4. Segredos

```sh
cp .env.example .env
openssl rand -base64 32   # → POSTGRES_PASSWORD
openssl rand -base64 48   # → SOLARSYNC_JWT_SEGREDO
nano .env
```

Preencha também `SOLARSYNC_ADMIN_SENHA_INICIAL` — é a **única** forma de entrar num banco novo,
porque não há auto-cadastro nem login federado. E confira `SOLARSYNC_CORS_ORIGENS`: tem de ser
exatamente a origem do frontend, com `https://` e sem barra no fim.

Depois:

```sh
chmod +x deploy.sh backup.sh
./deploy.sh
```

O primeiro build leva alguns minutos (Maven baixando dependências). O script espera o
healthcheck e mostra o log se a aplicação não subir.

### 5. Frontend no Cloudflare Pages

No painel do Cloudflare → Workers & Pages → Create → Pages → conectar o repositório
`gmarqueszx/solarsync-web`:

| Campo | Valor |
|---|---|
| Framework preset | Vite |
| Build command | `npm run build` |
| Output directory | `dist` |
| Variável de ambiente | `VITE_API_URL` = `https://api.solarsync.conectsol.com` |

> ⚠️ `VITE_API_URL` é lida **no build**, não em tempo de execução (`src/api/client.ts`). Mudar a
> variável depois exige um novo build — um "Retry deployment" no painel basta.

Depois, em Custom domains, adicione `solarsync.conectsol.com`.

### 6. Conferir

```sh
curl -sI https://api.solarsync.conectsol.com/api/auth/login   # 401/405, e certificado válido
curl -s  https://api.solarsync.conectsol.com/actuator/health  # 404 — bloqueado de fora, correto
```

Abra `https://solarsync.conectsol.com`, entre com `joaogabriel@conectsol.com` e a senha inicial,
e confira no Dashboard que os 26 clientes de exemplo apareceram.

## Deploys seguintes

```sh
cd /opt/solarsync/solarsync/deploy && ./deploy.sh
```

**Antes de subir, rode `./mvnw test` na sua máquina.** A suíte exige Docker e não roda dentro do
build da imagem; o servidor de homologação não é lugar de descobrir teste vermelho.

## Backup

`./backup.sh` gera um dump comprimido em `deploy/backups/` e descarta o que passou de 14 dias.
Coloque no cron:

```
15 3 * * * /opt/solarsync/solarsync/deploy/backup.sh >> /opt/solarsync/backup.log 2>&1
```

Enquanto for homologação com dados de exemplo, a cópia local basta. **No dia em que a planilha
for importada**, copiar esses arquivos para fora do VPS deixa de ser opcional: é aí que passa a
existir dado que não está em nenhum outro lugar.

## O que muda em produção

Mesmo diretório clonado noutra pasta, `.env` próprio, e:

| Variável | Homologação | Produção |
|---|---|---|
| `SOLARSYNC_AMBIENTE` | `homologacao` | `producao` (separa volumes e containers) |
| `SOLARSYNC_API_DOMINIO` | `api.solarsync...` | o domínio definitivo |
| `SOLARSYNC_DADOS_DE_EXEMPLO` | `true` | **`false`** |
| `SOLARSYNC_NECTAR_ATIVO` | `false` | `true` |
| `SOLARSYNC_GMAIL_ATIVO` | `false` | `true`, com `SOMENTE_CONFERENCIA=true` no começo |
| `SOLARSYNC_NECTAR_SAIDA_ATIVO` | `false` | `true`, com `SOMENTE_CONFERENCIA=true` no começo |

> ⚠️ As integrações ficam **desligadas em homologação** de propósito. O job do Nectar lê o CRM de
> verdade da empresa, o da saída **escreve** nele, e o do Gmail lê a caixa real — ligados num
> ambiente de teste, a equipe treinaria em cima de dados de produção e o CRM começaria a se mexer
> sozinho por causa de um clique de ensaio.

E, antes de produção, os dois itens do checklist da seção 8 que continuam abertos: a auditoria de
segurança (item 10) e o WAF do Cloudflare na frente (item 11) — que é ligar a nuvem laranja no
registro do `api`, depois que o certificado já existir.

> ⚠️ Ao ligar a nuvem laranja, feche a porta 443 do firewall para tudo que não seja
> [os IPs do Cloudflare](https://www.cloudflare.com/ips/). Com o proxy ligado e o IP do servidor
> ainda alcançável direto, qualquer um contorna o WAF — e, pior, chega ao Caddy podendo forjar o
> `X-Forwarded-For`, que é o cabeçalho de onde sai o limite de tentativas por origem. O limite
> continuaria existindo e deixaria de valer para quem soubesse o IP.
