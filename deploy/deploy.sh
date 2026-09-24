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

if [[ ! -f .env ]]; then
	echo "ERRO: deploy/.env não existe. Copie o .env.example e preencha os segredos." >&2
	exit 1
fi

# A suíte exige Docker e um Postgres efêmero (Testcontainers) e não roda dentro do build da
# imagem. Rodá-la aqui é possível, mas o servidor de homologação é pequeno e a suíte é lenta —
# a regra é rodar `./mvnw test` na máquina de quem publica ANTES de vir para cá.
echo ">> Trazendo o código"
git pull --ff-only

echo ">> Construindo e subindo"
docker compose up -d --build

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
