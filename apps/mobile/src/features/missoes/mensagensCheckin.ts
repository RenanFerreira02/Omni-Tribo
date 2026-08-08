import type { ErroApi } from '@/api/erros';

export interface OrientacaoCheckin {
  titulo: string;
  /** O que o usuário deve FAZER. É isto que muda entre as três rejeições. */
  instrucao: string;
  /** Insistir do mesmo lugar, sem mudar nada, tem chance de dar certo? */
  vaiAdiantarTentarDeNovo: boolean;
}

/**
 * Traduz a recusa do check-in em orientação.
 *
 * Este arquivo é a razão de existir do ADR 0010: as três rejeições são o MESMO 422 e pedem ações
 * mutuamente inúteis. Mandar quem está com mock location ligado "aproximar-se do local" o faz
 * caminhar até lá para falhar de novo no mesmo ponto; mandar quem está longe "procurar céu aberto"
 * não muda nada. A escolha sai do `type`, jamais do `detail` — que é a frase do backend, exibida
 * como está e livre para mudar.
 */
export function orientacaoDe(erro: ErroApi): OrientacaoCheckin {
  switch (erro.tipo) {
    case 'checkinLocalizacaoSimulada':
      return {
        titulo: 'Localização simulada',
        instrucao:
          'Desative a localização simulada nas opções de desenvolvedor do aparelho e tente de novo. ' +
          'Andar até o local não resolve enquanto ela estiver ligada.',
        vaiAdiantarTentarDeNovo: false,
      };
    case 'checkinAcuraciaInsuficiente':
      return {
        titulo: 'Sinal de GPS impreciso',
        instrucao:
          'Saia de baixo de cobertura, aguarde alguns segundos para o GPS estabilizar e tente de novo — ' +
          'você pode continuar no mesmo lugar.',
        vaiAdiantarTentarDeNovo: true,
      };
    case 'checkinForaDoRaio':
      return {
        titulo: 'Você ainda não chegou',
        instrucao: erro.detail,
        vaiAdiantarTentarDeNovo: false,
      };
    case 'transicaoInvalida':
      return {
        titulo: 'Esta missão mudou de estado',
        instrucao: 'Recarregue a tela para ver a situação atual.',
        vaiAdiantarTentarDeNovo: false,
      };
    case 'conflitoConcorrencia':
      return {
        titulo: 'Alguém alterou a missão agora',
        instrucao: 'Tente novamente.',
        vaiAdiantarTentarDeNovo: true,
      };
    default:
      return {
        titulo: 'Não foi possível fazer o check-in',
        instrucao: erro.detail,
        vaiAdiantarTentarDeNovo: erro.tipo === 'semRede',
      };
  }
}
