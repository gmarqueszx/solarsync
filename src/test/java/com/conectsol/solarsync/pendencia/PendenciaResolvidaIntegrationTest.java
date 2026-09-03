package com.conectsol.solarsync.pendencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.StatusProjeto;

/**
 * Prova ponta a ponta do requisito de integração entre etapas (CLAUDE.md seção 3):
 * resolver uma Pendencia deve criar automaticamente um Projeto e auditar ambas as
 * transições em historico_status. Sem @Transactional de teste: o listener que cria o
 * Projeto roda em AFTER_COMMIT, então a transação precisa realmente comitar.
 */
class PendenciaResolvidaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PendenciaRepository pendenciaRepository;

    @Autowired
    private PendenciaService pendenciaService;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        usuarioRepository.deleteAll();
        clienteRepository.deleteAll();
    }

    @Test
    void resolverPendenciaCriaProjetoEAuditaAsDuasTransicoes() {
        Cliente cliente = clienteRepository.save(Cliente.builder().nome("Cliente Integração").build());
        Usuario analista = usuarioRepository.save(Usuario.builder()
                .nome("Analista Teste")
                .email("analista.teste@conectsol.com")
                .build());
        Pendencia pendencia = pendenciaRepository.save(Pendencia.builder()
                .cliente(cliente)
                .tipo(TipoPendencia.LIGACAO_NOVA)
                .status(StatusPendencia.EM_ANDAMENTO)
                .solicitadoEm(Instant.now())
                .responsavel(analista)
                .build());

        pendenciaService.atualizarStatus(pendencia.getId(), StatusPendencia.RESOLVIDA, analista.getId());

        List<Projeto> projetosDoCliente = projetoRepository.findByClienteId(cliente.getId());
        assertThat(projetosDoCliente).hasSize(1);
        assertThat(projetosDoCliente.get(0).getStatus()).isEqualTo(StatusProjeto.RECEBIDO);

        assertThat(historicoStatusRepository.findByEntidadeTipoAndEntidadeId(
                EntidadeTipo.PENDENCIA, pendencia.getId())).hasSize(1);
        assertThat(historicoStatusRepository.findByEntidadeTipoAndEntidadeId(
                EntidadeTipo.PROJETO, projetosDoCliente.get(0).getId())).hasSize(1);
    }
}
