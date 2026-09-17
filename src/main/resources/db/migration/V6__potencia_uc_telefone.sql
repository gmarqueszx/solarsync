-- Campos que o protótipo do frontend assumia e o modelo não tinha. Confirmados com o usuário
-- como informação real do dia a dia.

-- Potência da usina em kWp. É o atributo que define o porte do projeto, e permite ao gestor
-- somar kWp homologado por período — número que hoje ninguém consegue ver.
ALTER TABLE projeto ADD COLUMN potencia_kwp NUMERIC(8,2);

-- Unidade Consumidora da Coelba: toda interação com a concessionária referencia a UC, então
-- ela vira chave de busca nas telas. Sem UNIQUE de propósito: um cliente pode ter mais de uma
-- UC — é justamente disso que trata a etapa de unificação. Aqui fica a UC principal.
ALTER TABLE cliente ADD COLUMN uc_coelba VARCHAR(30);

ALTER TABLE cliente ADD COLUMN telefone VARCHAR(20);

CREATE INDEX idx_cliente_uc_coelba ON cliente (uc_coelba);
