package com.omnitribo.geolocalizacao.dominio;

import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.geolocalizacao.api.ComandoCheckin;
import com.omnitribo.geolocalizacao.api.RegistroCheckin;
import com.omnitribo.geolocalizacao.api.ResultadoCheckin;
import com.omnitribo.geolocalizacao.infra.CheckinRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avalia e grava check-ins. Implementação da porta {@link RegistroCheckin}.
 *
 * <p>Roda na transação do CHAMADOR ({@code REQUIRED}), de propósito, e isso já foi diferente: a
 * primeira versão usava {@code REQUIRES_NEW} para que a linha de auditoria de uma rejeição
 * sobrevivesse ao rollback do 422. Funcionava, e criava um defeito grave — a transação externa
 * segura {@code SELECT ... FOR UPDATE} sobre a missão enquanto a interna pede uma SEGUNDA conexão.
 * Com N requisições simultâneas e pool de tamanho P, bastava N ≥ P para todas as conexões estarem
 * presas em transações externas esperando por internas que nunca viriam: deadlock de pool, 30 s de
 * timeout e 500 para todo mundo — inclusive para quem só queria fazer login. {@code
 * CheckinConcorrenteTest} reproduzia isso com 50 threads.
 *
 * <p>A rejeição continua sendo gravada. O que mudou é ONDE o 422 é lançado: fora da transação, no
 * controller, depois do commit. Ver {@code MissaoService.registrarCheckin} e {@code
 * MissaoController.checkin}. O caminho aceito ficou de quebra genuinamente atômico — linha de
 * check-in, transição e trilha no mesmo commit —, o que eliminou a janela de check-in órfão que a
 * versão anterior assumia como custo.
 *
 * <p>Este serviço não toca a tabela {@code missao}, nem por leitura. Não é mais por deadlock, e sim
 * por fronteira de módulo: a regra do ArchUnit proíbe geolocalizacao de acessar missoes.dominio.
 * Ver {@link ComandoCheckin}.
 */
@Service
public class RegistroCheckinService implements RegistroCheckin {

  private final CheckinRepository checkinRepository;
  private final ConsultasGeoespaciais consultasGeoespaciais;

  public RegistroCheckinService(
      CheckinRepository checkinRepository, ConsultasGeoespaciais consultasGeoespaciais) {
    this.checkinRepository = checkinRepository;
    this.consultasGeoespaciais = consultasGeoespaciais;
  }

  /**
   * Leitura pura, sem REQUIRES_NEW: participa da transação do chamador porque só toca {@code
   * checkin}, tabela que a transação externa não bloqueia. Abrir transação nova aqui custaria uma
   * conexão a mais por requisição sem isolar nada.
   */
  @Override
  @Transactional(readOnly = true)
  public Optional<ResultadoCheckin> consultar(String chaveIdempotencia) {
    return checkinRepository.findByChaveIdempotencia(chaveIdempotencia).map(this::replayDe);
  }

  @Override
  @Transactional
  public ResultadoCheckin registrar(ComandoCheckin comando) {
    Optional<Checkin> jaRegistrado =
        checkinRepository.findByChaveIdempotencia(comando.chaveIdempotencia());
    if (jaRegistrado.isPresent()) {
      return replayDe(jaRegistrado.get());
    }

    // Distância medida no servidor. O cliente informa apenas onde diz estar; a régua é nossa.
    BigDecimal distanciaM =
        BigDecimal.valueOf(
                consultasGeoespaciais.distanciaMetros(
                    comando.lat(), comando.lon(), comando.origemLat(), comando.origemLon()))
            .setScale(2, RoundingMode.HALF_UP);

    Checkin anterior =
        checkinRepository.findFirstByUsuarioIdOrderByCriadoEmDesc(comando.usuarioId()).orElse(null);

    BigDecimal distanciaDoAnterior = null;
    BigDecimal latAnterior = null;
    BigDecimal lonAnterior = null;
    if (anterior != null) {
      latAnterior = Coordenadas.latitude(anterior.getPonto());
      lonAnterior = Coordenadas.longitude(anterior.getPonto());
      distanciaDoAnterior =
          BigDecimal.valueOf(
              consultasGeoespaciais.distanciaMetros(
                  latAnterior, lonAnterior, comando.lat(), comando.lon()));
    }

    AvaliacaoAntifraude.Avaliacao avaliacao =
        AvaliacaoAntifraude.avaliar(
            distanciaM,
            comando.acuraciaM(),
            comando.mocked(),
            comando.raioCheckinM(),
            latAnterior,
            lonAnterior,
            distanciaDoAnterior,
            anterior == null ? null : anterior.getCriadoEm(),
            comando.agora());

    Checkin checkin =
        new Checkin(
            UUID.randomUUID(),
            comando.missaoId(),
            comando.usuarioId(),
            Coordenadas.ponto(comando.lat(), comando.lon()),
            comando.acuraciaM(),
            distanciaM,
            MetodoCheckin.GPS,
            comando.mocked(),
            avaliacao.velocidadeImplicitaKmh(),
            avaliacao.aceito(),
            avaliacao.codigoRejeicao(),
            avaliacao.motivoRejeicao(),
            avaliacao.veredito() == ResultadoCheckin.Veredito.ACEITO_SUSPEITO,
            comando.chaveIdempotencia(),
            comando.agora());

    // saveAndFlush, e não save: força o INSERT agora, dentro da transação do chamador, em vez de
    // adiar para o commit. uk_checkin_idempotencia é a garantia final de unicidade — mas duas
    // requisições com a mesma chave são, por construção, da mesma missão (o missaoId entra no
    // material do hash), e o SELECT ... FOR UPDATE que o chamador segura sobre a linha da missão as
    // serializa antes de qualquer uma chegar aqui. Não há recuperação em catch porque não há
    // corrida a recuperar; se a constraint algum dia disparar, é sinal de que essa serialização
    // deixou de valer, e falhar alto é melhor do que mascarar. Provado por CheckinConcorrenteTest.
    checkinRepository.saveAndFlush(checkin);

    return new ResultadoCheckin(
        checkin.getId(),
        avaliacao.veredito(),
        distanciaM,
        comando.acuraciaM(),
        avaliacao.velocidadeImplicitaKmh(),
        avaliacao.codigoRejeicao(),
        avaliacao.motivoRejeicao(),
        false);
  }

  private ResultadoCheckin replayDe(Checkin checkin) {
    ResultadoCheckin.Veredito veredito;
    if (!checkin.isValido()) {
      veredito = ResultadoCheckin.Veredito.REJEITADO;
    } else if (checkin.isSuspeito()) {
      veredito = ResultadoCheckin.Veredito.ACEITO_SUSPEITO;
    } else {
      veredito = ResultadoCheckin.Veredito.ACEITO;
    }

    return new ResultadoCheckin(
        checkin.getId(),
        veredito,
        checkin.getDistanciaAlvoM(),
        checkin.getAcuraciaM(),
        checkin.getVelocidadeImplicitaKmh(),
        // Vem da linha persistida, não de um novo cálculo: é o que faz o replay de uma rejeição
        // responder o MESMO `type` da primeira tentativa. Ver V17.
        checkin.getCodigoRejeicao(),
        checkin.getMotivoRejeicao(),
        true);
  }
}
