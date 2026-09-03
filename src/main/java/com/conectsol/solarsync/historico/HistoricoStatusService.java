package com.conectsol.solarsync.historico;

import org.springframework.stereotype.Service;

import com.conectsol.solarsync.common.event.EntidadeStatusEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HistoricoStatusService {

    private final HistoricoStatusRepository historicoStatusRepository;

    public void registrar(EntidadeStatusEvent evento) {
        HistoricoStatus historico = HistoricoStatus.builder()
                .entidadeTipo(evento.getEntidadeTipo())
                .entidadeId(evento.getEntidadeId())
                .statusAnterior(evento.getStatusAnterior())
                .statusNovo(evento.getStatusNovo())
                .ocorridoEm(evento.getOcorridoEm())
                .usuarioId(evento.getUsuarioId())
                .build();

        historicoStatusRepository.save(historico);
    }
}
