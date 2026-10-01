#!/usr/bin/env bash
#
# Publica a versão atual do repositório no servidor. Rode NO SERVIDOR, de dentro de deploy/:
#
#     ./deploy.sh
#
# O que ele faz: traz o código, reconstrói a imagem da API, sobe a pilha e espera o healthcheck
# ficar verde antes de dizer que deu certo. Se a aplicação não subir, ele mostra o log e sai com
# erro — em vez de terminar em silêncio com o sistema fora do ar, que é o modo de falhar que
# mais custa caro num deploy manual.

set -euo pipefail

cd "$(dirname "$0")"

# Os workflows do backend e do frontend (.github/workflows/deploy.yml) disparam este script cada um
# por conta própria, e um push nos dois repositórios ao mesmo tempo rodaria dois builds sobre a
# mesma pilha. A trava faz o segundo esperar o primeiro terminar — e, como ele dá `git pull` nos
# dois, o segundo publica o que o primeiro já trouxe mais o que chegou depois.
exec 9>/tmp/solarsync-deploy.lock
if ! flock -w 1200 9; then
	echo "ERRO: outro deploy está rodando há mais de 20 minutos." >&2
	exit 1
fi

if [[ ! -f .env ]]; then
	echo "ERRO: deploy/.env não existe. Copie o .env.example e preencha os segredos." >&2
	exit 1
fi

# O compose lê o .env sozinho para interpolar o compose.yaml, mas as variáveis não chegam a este
# script — e é daqui que sai o caminho do clone do frontend, logo abaixo.
set -a
# shellcheck source=/dev/null
. ./.env
set +a

# A suíte exige Docker e um Postgres efêmero (Testcontainers) e não roda dentro do build da
# imagem. Rodá-la aqui é possível, mas o servidor de homologação é pequeno e a suíte é lenta —
# a regra é rodar `./mvnw test` na máquina de quem publica ANTES de vir para cá.

# A pilha é construída de DOIS repositórios: este e o do frontend, que vira a imagem do Caddy.
# Atualizar só este publicaria backend novo com a tela velha — e nada acusaria, porque tudo
# sobe saudável.
front="${SOLARSYNC_FRONT_CAMINHO:-../../solarsync-front}"
if [[ ! -d "$front/.git" ]]; then
	echo "ERRO: não achei o clone do frontend em '$front'." >&2
	echo "      Os dois repositórios ficam lado a lado — ver deploy/README.md, passo 3." >&2
	exit 1
fi

echo ">> Trazendo o código (backend)"
git pull --ff-only

echo ">> Trazendo o código (frontend)"
git -C "$front" pull --ff-only

# A rede e a pasta que o Caddy compartilha com os outros sistemas do VPS (ver compose.yaml).
docker network inspect edge >/dev/null 2>&1 || docker network create edge
mkdir -p /opt/caddy-sites

# ⚠️ Construir e subir em passos SEPARADOS, e não com `up -d --build`. Observado em 24/09/2026:
# com o contexto de build errado, o `up --build` imprimiu o erro e mesmo assim **saiu com código
# 0**, deixando a pilha inteira sem subir. Num script com `set -e` isso passa batido, e o erro só
# apareceria cinco minutos depois, como "a API não ficou saudável" — que aponta para o lugar
# errado. Com `build` à parte, a falha é a falha.
echo ">> Construindo as imagens"
if ! docker compose build; then
	echo "ERRO: o build falhou. Nada foi alterado no que está no ar." >&2
	exit 1
fi

echo ">> Subindo"
docker compose up -d

echo ">> Esperando a API ficar saudável (o Flyway roda antes do primeiro OK)"
for _ in $(seq 1 60); do
	estado=$(docker compose ps --format '{{.Health}}' api 2>/dev/null || true)
	if [[ "$estado" == "healthy" ]]; then
		echo ">> API no ar"
		docker compose ps
		exit 0
	fi
	if [[ "$estado" == "unhealthy" ]]; then
		break
	fi
	sleep 5
done

echo "ERRO: a API não ficou saudável. Últimas 80 linhas do log:" >&2
docker compose logs --tail 80 api >&2
exit 1
