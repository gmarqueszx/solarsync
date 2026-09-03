package com.conectsol.solarsync.unificacao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.unificacao.dto.UnificacaoFiltro;
import com.conectsol.solarsync.unificacao.dto.UnificacaoRequest;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Etapa 4 do fluxo: unificação de unidades consumidoras e desligamento do medidor antigo.
 * <p>
 * Ao contrário dos outros módulos, não publica evento de status e não tem máquina de estados:
 * a entidade não tem um campo de status, e sim dois marcos independentes ({@code feita} e
 * {@code desligamento}) que podem acontecer em qualquer ordem. Inventar um status aqui só para
 * uniformizar criaria uma modelagem que o processo real não tem — e o dashboard (seção 5) não
 * pede nenhuma métrica de tempo de unificação.
 */
@Service
@RequiredArgsConstructor
public class UnificacaoService {

    private final UnificacaoRepository unificacaoRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public Page<Unificacao> listar(UnificacaoFiltro filtro, Pageable paginacao) {
        return unificacaoRepository.findAll(UnificacaoSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public Unificacao buscar(Long id) {
        return carregar(id);
    }

    @Transactional
    public Unificacao criar(UnificacaoRequest requisicao) {
        Cliente cliente = clienteRepository.findById(requisicao.clienteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cliente não encontrado: " + requisicao.clienteId()));

        return unificacaoRepository.save(Unificacao.builder()
                .cliente(cliente)
                .cidade(requisicao.cidade() == null ? cliente.getCidade() : requisicao.cidade())
                .projetista(resolverProjetista(requisicao.projetistaId()))
                .informacoes(requisicao.informacoes())
                .feita(false)
                .desligamento(false)
                .build());
    }

    @Transactional
    public Unificacao atualizar(Long id, UnificacaoRequest requisicao) {
        Unificacao unificacao = carregar(id);
        unificacao.setCidade(requisicao.cidade());
        unificacao.setProjetista(resolverProjetista(requisicao.projetistaId()));
        unificacao.setInformacoes(requisicao.informacoes());
        return unificacaoRepository.save(unificacao);
    }

    @Transactional
    public Unificacao marcarFeita(Long id, boolean feita) {
        Unificacao unificacao = carregar(id);
        unificacao.setFeita(feita);
        return unificacaoRepository.save(unificacao);
    }

    @Transactional
    public Unificacao marcarDesligamento(Long id, boolean desligamento) {
        Unificacao unificacao = carregar(id);
        unificacao.setDesligamento(desligamento);
        return unificacaoRepository.save(unificacao);
    }

    @Transactional
    public void excluir(Long id) {
        unificacaoRepository.delete(carregar(id));
    }

    private Usuario resolverProjetista(Long projetistaId) {
        if (projetistaId == null) {
            return null;
        }
        return usuarioRepository.findById(projetistaId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Usuário não encontrado: " + projetistaId));
    }

    private Unificacao carregar(Long id) {
        return unificacaoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unificação não encontrada: " + id));
    }
}
