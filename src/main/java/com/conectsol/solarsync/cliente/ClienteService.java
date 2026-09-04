package com.conectsol.solarsync.cliente;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.cliente.dto.ClienteResponse;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ClienteService {

    private final ClienteRepository clienteRepository;

    @Transactional(readOnly = true)
    public Page<Cliente> listar(String busca, Pageable paginacao) {
        if (busca == null || busca.isBlank()) {
            return clienteRepository.findAll(paginacao);
        }
        String termo = busca.trim();
        return clienteRepository.findByNomeContainingIgnoreCaseOrUcCoelbaContainingIgnoreCase(
                termo, termo, paginacao);
    }

    @Transactional(readOnly = true)
    public ClienteResponse buscar(Long id) {
        return ClienteResponse.de(carregar(id));
    }

    @Transactional
    public ClienteResponse criar(ClienteRequest requisicao) {
        Cliente cliente = Cliente.builder()
                .nome(requisicao.nome())
                .cidade(requisicao.cidade())
                .vendedor(requisicao.vendedor())
                .dataPagamento(requisicao.dataPagamento())
                .ucCoelba(requisicao.ucCoelba())
                .telefone(requisicao.telefone())
                .build();
        return ClienteResponse.de(clienteRepository.save(cliente));
    }

    @Transactional
    public ClienteResponse atualizar(Long id, ClienteRequest requisicao) {
        Cliente cliente = carregar(id);
        cliente.setNome(requisicao.nome());
        cliente.setCidade(requisicao.cidade());
        cliente.setVendedor(requisicao.vendedor());
        cliente.setDataPagamento(requisicao.dataPagamento());
        cliente.setUcCoelba(requisicao.ucCoelba());
        cliente.setTelefone(requisicao.telefone());
        return ClienteResponse.de(clienteRepository.save(cliente));
    }

    @Transactional
    public void excluir(Long id) {
        clienteRepository.delete(carregar(id));
    }

    Cliente carregar(Long id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Cliente não encontrado: " + id));
    }
}
