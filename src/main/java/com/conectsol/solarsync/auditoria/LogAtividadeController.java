package com.conectsol.solarsync.auditoria;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.auditoria.dto.LogAtividadeFiltro;
import com.conectsol.solarsync.auditoria.dto.LogAtividadeResponse;
import com.conectsol.solarsync.common.security.GerenciaUsuarios;
import com.conectsol.solarsync.common.web.PaginaResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Só leitura, e só GESTOR e ADMINISTRADOR: o log mostra o que cada pessoa fez, inclusive na
 * gestão de usuários (quem redefiniu a senha de quem), e serve para auditar a equipe — mesmo
 * recorte de quem gerencia usuários. Não existe — e não deve passar a existir — endpoint de
 * escrita: um log que aceita inserção à mão não prova nada.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Tag(name = "Log de atividades", description = "Quem criou, editou ou excluiu cada registro")
public class LogAtividadeController {

    private final LogAtividadeRepository logAtividadeRepository;

    @GetMapping
    @GerenciaUsuarios
    @Transactional(readOnly = true)
    @Operation(
            summary = "Lista o log de atividades, do mais recente para o mais antigo",
            description = "Toda criação, edição e exclusão de cliente, pendência, débito, "
                    + "projeto, vistoria, unificação e usuário, com o antes/depois de cada campo. "
                    + "Mudança de status aparece como edição do campo `status`. `usuarioId` nulo "
                    + "na resposta é ação do sistema (integrações). Filtros opcionais: "
                    + "`usuarioId`, `entidadeTipo` + `entidadeId` (histórico de um registro), "
                    + "`acao`, `de`/`ate` (dias, fuso da Bahia) e `q` (nome do cliente/usuário).")
    public PaginaResponse<LogAtividadeResponse> listar(LogAtividadeFiltro filtro,
            @PageableDefault(size = 50) Pageable paginacao) {
        return PaginaResponse.de(logAtividadeRepository.listar(filtro, paginacao), l -> l);
    }
}
