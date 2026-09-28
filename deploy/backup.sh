#!/usr/bin/env bash
#
# Cópia do banco para um arquivo comprimido em deploy/backups/, mantendo os 14 dias mais
# recentes. Rode no servidor, de dentro de deploy/:
#
#     ./backup.sh
#
# E, para não depender de alguém lembrar, ponha no cron do usuário (crontab -e):
#
#     15 3 * * * /opt/solarsync/solarsync/deploy/backup.sh >> /opt/solarsync/backup.log 2>&1
#
# ⚠️ Isto é cópia LOCAL: protege de erro de operação (um DELETE errado, uma migration ruim), não
# da perda do servidor. Enquanto for homologação com dados de exemplo, está de bom tamanho. No
# dia em que a planilha for importada, copiar estes arquivos para fora do VPS deixa de ser
# opcional — é aí que existe dado que ninguém tem em outro lugar.

set -euo pipefail

# O dump é o banco inteiro — clientes, débitos, hashes de senha. Sem isto ele nasceria legível
# por qualquer usuário do servidor (umask padrão 022).
umask 077

cd "$(dirname "$0")"

# O compose lê o .env sozinho para interpolar o compose.yaml, mas as variáveis não chegam a este
# script — sem isto, POSTGRES_USER cairia no padrão e o pg_dump falharia num banco renomeado.
set -a
# shellcheck source=/dev/null
. ./.env
set +a

destino="backups"
mkdir -p "$destino"

arquivo="$destino/solarsync-$(date +%Y%m%d-%H%M%S).sql.gz"

# --clean --if-exists para o arquivo poder ser restaurado sobre um banco que já tem as tabelas.
docker compose exec -T postgres \
	pg_dump --username "${POSTGRES_USER:-solarsync}" --clean --if-exists "${POSTGRES_DB:-solarsync}" \
	| gzip > "$arquivo"

echo "Backup em $arquivo ($(du -h "$arquivo" | cut -f1))"

# Descarta o que passou de 14 dias. Sem isto o disco do VPS enche em silêncio.
find "$destino" -name 'solarsync-*.sql.gz' -mtime +14 -delete

# Restaurar:
#   gunzip -c backups/solarsync-AAAAMMDD-HHMMSS.sql.gz \
#     | docker compose exec -T postgres psql --username solarsync solarsync
