package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.ProvisionamentoCarteira;
import com.omnitribo.carteira.infra.CarteiraRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link ProvisionamentoCarteira}. */
@Service
public class ProvisionamentoCarteiraService implements ProvisionamentoCarteira {

  private final CarteiraRepository carteiraRepository;

  public ProvisionamentoCarteiraService(CarteiraRepository carteiraRepository) {
    this.carteiraRepository = carteiraRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID garantirCarteira(UUID usuarioId) {
    // Projeção escalar, não findByUsuarioId: carregar a entidade aqui a poria no persistence
    // context, e um buscarParaAtualizar posterior na mesma transação devolveria a instância em
    // cache sem reemitir o SELECT ... FOR UPDATE.
    Optional<UUID> existente = carteiraRepository.buscarIdPorUsuario(usuarioId);
    if (existente.isPresent()) {
      return existente.get();
    }

    Carteira carteira = new Carteira(UUID.randomUUID(), usuarioId);
    carteiraRepository.save(carteira);
    return carteira.getId();
  }
}
