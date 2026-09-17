-- Triagem de pendência: "este cliente foi checado na Coelba?" precisava ser um fato registrado.
-- Antes, cliente sem pendência não gerava registro nenhum, então não havia como distinguir
-- "checado, não tem nada" de "ninguém olhou ainda" — e é o segundo que se perde de vista.

ALTER TABLE cliente
    ADD COLUMN status_triagem VARCHAR(30) NOT NULL DEFAULT 'AGUARDANDO_VERIFICACAO';

ALTER TABLE cliente
    ADD CONSTRAINT ck_cliente_status_triagem
    CHECK (status_triagem IN ('AGUARDANDO_VERIFICACAO', 'COM_PENDENCIA', 'SEM_PENDENCIA'));

-- Cliente que já tem pendência registrada foi checado por definição: a pendência é a prova.
UPDATE cliente c
   SET status_triagem = 'COM_PENDENCIA'
 WHERE EXISTS (SELECT 1 FROM pendencia p WHERE p.cliente_id = c.id);

-- Quem já tem projeto e nunca teve pendência passou pela triagem pelo caminho "segue direto".
-- Sem este UPDATE, todo cliente em andamento reapareceria na fila de verificação como se
-- nunca tivesse sido olhado — a fila nasceria mentindo.
UPDATE cliente c
   SET status_triagem = 'SEM_PENDENCIA'
 WHERE c.status_triagem = 'AGUARDANDO_VERIFICACAO'
   AND EXISTS (SELECT 1 FROM projeto p WHERE p.cliente_id = c.id);

-- A fila da triagem é consultada por status; sem índice ela varre a tabela inteira.
CREATE INDEX idx_cliente_status_triagem ON cliente (status_triagem);
