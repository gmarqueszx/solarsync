# Deploy do SolarSync

Runbook do ambiente de **homologação** — o que a equipe de Projetos usa para testar antes de a
planilha ser substituída de verdade. Produção é a mesma pilha com outro `.env`; a seção final
diz o que muda.

## O desenho

```
  navegador ── https://solarsync.conectsol.com ──► VPS
                                                    │
                                                    ├── caddy    :80 :443
                                                    │     ├── /        a interface (estáticos)
                                                    │     └── /api/*   proxy para a API
                                                    ├── api      :8080  (rede interna)
                                                    └── postgres :5432  (rede interna)
```

**Um domínio só, e é o ponto principal do desenho.** A tela em `/` e a API em `/api/*` no mesmo
host significa requisição de mesma origem: sem CORS, sem preflight, e sem uma lista de origens no
servidor para alguém errar — que é o erro mais comum e mais confuso de diagnosticar, porque a
tela carrega normalmente e nenhuma requisição funciona.

**Por que um VPS e não Cloud Run**: o SolarSync tem trabalho agendado — o job do Nectar a cada
10 min, o do Gmail a cada 15, o reprocessamento da saída a cada 30. Em Cloud Run com escala a
zero esses jobs **não rodam** quando não há requisição, e não rodam *em silêncio*: nenhum erro,
nenhum log, os clientes só param de entrar. Manter uma instância sempre ligada para evitar isso
custa mais que o VPS e ainda exige banco gerenciado à parte.

**Duas imagens, dois repositórios.** A da API sai daqui; a do Caddy sai do `solarsync-web` e é um
Caddy com o `dist` do Vite dentro. Cada repositório sabe construir a si mesmo; o desenho da pilha
(o `Caddyfile`, o compose) fica só aqui.

## O que você precisa ter em mãos

1. **Um VPS** com Ubuntu 24.04, mínimo **2 vCPU / 4 GB RAM / 40 GB**. O build roda no servidor —
   Maven e Vite, um de cada vez — e a JVM com o Postgres ao lado não cabe bem em 2 GB.
   > 💡 **Escolha a região pensando na Bahia.** Todo clique da tela vai ao servidor; um VPS na
   > Europa acrescenta uns 200 ms a cada ação, e isso é o que a equipe vai chamar de "o sistema
   > está lento". São Paulo é o ideal; um datacenter nos EUA é o meio-termo aceitável.
2. **Acesso ao DNS de `conectsol.com`** para criar um registro.
3. `docker` e `docker compose` no servidor: `curl -fsSL https://get.docker.com | sh`

## Primeira vez

### 1. DNS

Um registro `A` apontando para o IP do VPS:

| Nome | Tipo | Valor | Proxy Cloudflare |
|---|---|---|---|
| `solarsync` | A | IP do VPS | **DNS only** (nuvem cinza) |

> ⚠️ A nuvem **cinza** não é detalhe: com o proxy ligado antes do primeiro certificado, o desafio
> do Let's Encrypt não chega ao Caddy e a emissão falha. Ligue o proxy depois, se quiser o WAF
> do item 11.

### 2. Firewall

Só 22, 80 e 443 entram. O Postgres **não** tem porta publicada no compose, mas um firewall
fechado é a segunda camada:

```sh
ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp && ufw enable
```

### 3. O código no servidor

A `main` é a branch de deploy nos dois repositórios: é ela que o servidor clona e é ela que o
`deploy.sh` atualiza. O trabalho entra por PR da branch de desenvolvimento.

**Os dois clones ficam lado a lado** — é de onde o compose constrói a imagem do Caddy:

```sh
mkdir -p /opt/solarsync && cd /opt/solarsync
git clone -b main git@github.com:gmarqueszx/solarsync.git
git clone -b main git@github.com:gmarqueszx/solarsync-web.git solarsync-front
cd solarsync/deploy
```

Deve ficar assim, e o nome da pasta do frontend importa (é o padrão de `SOLARSYNC_FRONT_CAMINHO`):

```
/opt/solarsync/
├── solarsync/        ← este repositório, com deploy/
└── solarsync-front/  ← solarsync-web
```

> Os repositórios são privados, então o `clone` pede autenticação. O caminho com menos manutenção
> é uma **deploy key** somente-leitura por repositório: `ssh-keygen -t ed25519 -f ~/.ssh/solarsync
> -N ""` no servidor e a chave pública colada em Settings → Deploy keys de cada um. Um token
> pessoal também funciona, mas vence e leva junto o acesso a todos os seus outros repositórios.

### 4. Segredos

```sh
cp .env.example .env
openssl rand -base64 32   # → POSTGRES_PASSWORD
openssl rand -base64 48   # → SOLARSYNC_JWT_SEGREDO
nano .env
```

Preencha também `SOLARSYNC_ADMIN_SENHA_INICIAL` — é a **única** forma de entrar num banco novo,
porque não há auto-cadastro nem login federado. E ajuste `SOLARSYNC_DOMINIO`.

```sh
chmod +x deploy.sh backup.sh
./deploy.sh
```

O primeiro build leva alguns minutos (Maven baixando dependências, depois o `npm ci`). O script
constrói, sobe, espera o healthcheck e mostra o log se a aplicação não subir.

> ⚠️ Se aparecer `unable to prepare context: path ... not found`, o clone do frontend não está
> onde `SOLARSYNC_FRONT_CAMINHO` aponta — reveja o passo 3.

### 5. Conferir

```sh
curl -sI  https://solarsync.conectsol.com/             # 200, e certificado válido
curl -sI  https://solarsync.conectsol.com/api/auth/eu  # 401 — a API responde pelo mesmo host
curl -s   https://solarsync.conectsol.com/actuator/health  # 404 — bloqueado de fora, correto
```

Abra o domínio no navegador, entre com `joaogabriel@conectsol.com` e a senha inicial, e confira
no Dashboard que os 26 clientes de exemplo apareceram. **Olhe a aba Rede do navegador**: as
chamadas devem sair como `/api/...` no mesmo host, sem nenhuma requisição `OPTIONS` de preflight.
Se aparecer preflight, alguém embutiu um `VITE_API_URL` no build.

## Deploys seguintes

```sh
cd /opt/solarsync/solarsync/deploy && ./deploy.sh
```

O script atualiza os **dois** repositórios e reconstrói o que mudou.

**Antes de subir, rode `./mvnw test` na sua máquina.** A suíte exige Docker e não roda dentro do
build da imagem; o servidor não é lugar de descobrir teste vermelho.

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

Os dois repositórios clonados noutra pasta, `.env` próprio, e:

| Variável | Homologação | Produção |
|---|---|---|
| `SOLARSYNC_AMBIENTE` | `homologacao` | `producao` (separa volumes e containers) |
| `SOLARSYNC_DOMINIO` | `solarsync.conectsol.com` | o domínio definitivo |
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
registro, depois que o certificado já existir.

> ⚠️ Ao ligar a nuvem laranja, feche a porta 443 do firewall para tudo que não seja
> [os IPs do Cloudflare](https://www.cloudflare.com/ips/). Com o proxy ligado e o IP do servidor
> ainda alcançável direto, qualquer um contorna o WAF — e, pior, chega ao Caddy podendo forjar o
> `X-Forwarded-For`, que é o cabeçalho de onde sai o limite de tentativas por origem. O limite
> continuaria existindo e deixaria de valer para quem soubesse o IP.
