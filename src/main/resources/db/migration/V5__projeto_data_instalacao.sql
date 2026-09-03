-- Data em que a usina foi fisicamente instalada. Entrada manual: a Nycole recolhe a informação
-- no grupo "projetos instalados" e registra aqui.
--
-- É campo do projeto, e não um status: o status do projeto acompanha a homologação na Coelba
-- (decisão deles), enquanto a instalação é evento de campo. Sem essa data, a métrica "tempo
-- médio para solicitar vistoria pós-instalação" (seção 5 do CLAUDE.md) não tem de onde partir,
-- e é ela que mostra cliente instalado esperando vistoria.
ALTER TABLE projeto ADD COLUMN data_instalacao DATE;

CREATE INDEX idx_projeto_data_instalacao ON projeto (data_instalacao);
