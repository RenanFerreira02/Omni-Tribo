import type { AcaoMissao } from '@/api/missoes';
import type { MissaoResponse, StatusMissao } from '@/api/tipos';

/**
 * Que botões aparecem no detalhe de uma missão, dado o STATUS e o PAPEL de quem olha.
 *
 * Antes isto era uma cascata de `if` dentro da tela, com dois efeitos ruins. Primeiro, quatro dos
 * nove status não produziam botão NEM explicação — a tela ficava muda e o usuário não sabia se
 * estava esperando algo, se tinha perdido a vez ou se o app havia quebrado. Segundo, não havia como
 * testar "um caso por status" sem renderizar a tela inteira nove vezes.
 *
 * Como tabela, a matriz (status × papel) é exaustiva por construção: o `Record<StatusMissao, …>`
 * faz um status novo do backend quebrar o typecheck em vez de cair silenciosamente no ramo vazio.
 */

/** Quem está olhando. Deriva da missão + id do usuário; nunca vem do servidor. */
export type PapelNaMissao = 'CRIADOR' | 'EXECUTOR' | 'TERCEIRO';

export interface AcaoDisponivel {
  acao: AcaoMissao | 'checkin';
  rotulo: string;
  variante: 'primario' | 'secundario' | 'texto';
  /**
   * Exige confirmação antes de disparar.
   *
   * Marca o que não tem volta pela máquina de estados: desistir devolve a missão ao mercado e o
   * executor pode não reconseguir; cancelar e confirmar são terminais. Aceitar NÃO é irreversível
   * — quem aceitou pode desistir —, então pedir confirmação ali só somaria um toque ao caminho
   * mais comum do app.
   */
  irreversivel: boolean;
  /** Texto do diálogo de confirmação. Presente exatamente quando `irreversivel`. */
  confirmacao?: { titulo: string; mensagem: string };
  /**
   * Presente quando a ação aparece mas NÃO pode ser disparada.
   *
   * Botão desabilitado com o motivo à vista é melhor do que botão que falha depois do toque: a
   * pessoa descobre o requisito antes de gastar a ação, e não como um erro. A checagem definitiva
   * continua no servidor — este campo é conveniência de UI, nunca autorização.
   */
  bloqueio?: { motivo: string };
}

export interface EstadoDeAcoes {
  acoes: AcaoDisponivel[];
  /**
   * Por que não há nada a fazer. Nulo quando há ações.
   *
   * É o que a versão anterior não tinha: sem esta frase, "missão aceita por outra pessoa" e
   * "aguardando o criador confirmar" eram a mesma tela em branco.
   */
  explicacao: string | null;
}

export function papelNaMissao(
  missao: Pick<MissaoResponse, 'criadorId' | 'executorId'>,
  usuarioId: string | undefined,
): PapelNaMissao {
  if (!usuarioId) return 'TERCEIRO';
  if (missao.criadorId === usuarioId) return 'CRIADOR';
  if (missao.executorId === usuarioId) return 'EXECUTOR';
  return 'TERCEIRO';
}

const PUBLICAR: AcaoDisponivel = {
  acao: 'publicar',
  rotulo: 'Publicar missão',
  variante: 'primario',
  irreversivel: false,
};

const ACEITAR: AcaoDisponivel = {
  acao: 'aceitar',
  rotulo: 'Aceitar missão',
  variante: 'primario',
  irreversivel: false,
};

const INICIAR: AcaoDisponivel = {
  acao: 'iniciar',
  rotulo: 'Iniciar missão',
  variante: 'primario',
  irreversivel: false,
};

const CHECKIN: AcaoDisponivel = {
  acao: 'checkin',
  rotulo: 'Fazer check-in',
  variante: 'primario',
  irreversivel: false,
};

const CONFIRMAR: AcaoDisponivel = {
  acao: 'confirmar',
  rotulo: 'Confirmar conclusão',
  variante: 'primario',
  irreversivel: true,
  confirmacao: {
    titulo: 'Confirmar conclusão?',
    mensagem:
      'A recompensa em XP e tokens será creditada ao executor agora, e a missão passa a CONCLUÍDA. Não dá para desfazer.',
  },
};

const DESISTIR: AcaoDisponivel = {
  acao: 'desistir',
  rotulo: 'Desistir',
  variante: 'texto',
  irreversivel: true,
  confirmacao: {
    titulo: 'Desistir da missão?',
    mensagem: 'Ela volta para o mercado e outra pessoa pode aceitar antes de você mudar de ideia.',
  },
};

const CANCELAR: AcaoDisponivel = {
  acao: 'cancelar',
  rotulo: 'Cancelar missão',
  variante: 'secundario',
  irreversivel: true,
  confirmacao: {
    titulo: 'Cancelar missão?',
    mensagem:
      'A missão é encerrada e os tokens do pote voltam para quem financiou. Não dá para desfazer.',
  },
};

const CONTESTAR: AcaoDisponivel = {
  acao: 'contestar',
  rotulo: 'Contestar entrega',
  variante: 'secundario',
  irreversivel: true,
  confirmacao: {
    titulo: 'Contestar a entrega?',
    mensagem:
      'A missão vai para disputa e um administrador vai avaliar. Faça isso só se o combinado não foi cumprido.',
  },
};

const SEM_ACOES = (explicacao: string): EstadoDeAcoes => ({ acoes: [], explicacao });

/**
 * A matriz. Cada célula responde as duas perguntas da tela: o que dá para fazer, e — se nada — por
 * quê.
 *
 * `TERCEIRO` recebe explicação em quase todo lugar de propósito. Ele é quem navega pelo radar e
 * abre uma missão que já tem dono, e é o caso que mais frequentemente resultava em tela muda.
 */
const MATRIZ: Record<StatusMissao, Record<PapelNaMissao, EstadoDeAcoes>> = {
  RASCUNHO: {
    CRIADOR: { acoes: [PUBLICAR, CANCELAR], explicacao: null },
    // Não deveria acontecer — o backend responde 404 para rascunho alheio —, mas a tela não pode
    // depender disso para saber o que desenhar.
    EXECUTOR: SEM_ACOES('Esta missão ainda é um rascunho do criador.'),
    TERCEIRO: SEM_ACOES('Esta missão ainda é um rascunho do criador.'),
  },
  ABERTA: {
    CRIADOR: { acoes: [CANCELAR], explicacao: null },
    EXECUTOR: { acoes: [ACEITAR], explicacao: null },
    TERCEIRO: { acoes: [ACEITAR], explicacao: null },
  },
  ACEITA: {
    CRIADOR: { acoes: [CANCELAR], explicacao: null },
    EXECUTOR: { acoes: [INICIAR, DESISTIR], explicacao: null },
    TERCEIRO: SEM_ACOES('Outra pessoa já aceitou esta missão.'),
  },
  EM_ANDAMENTO: {
    CRIADOR: SEM_ACOES('O executor está a caminho. Você confirma quando ele fizer o check-in.'),
    EXECUTOR: { acoes: [CHECKIN, DESISTIR], explicacao: null },
    TERCEIRO: SEM_ACOES('Esta missão já está sendo executada por outra pessoa.'),
  },
  AGUARDANDO_CONFIRMACAO: {
    CRIADOR: { acoes: [CONFIRMAR, CONTESTAR], explicacao: null },
    EXECUTOR: SEM_ACOES('Check-in registrado. Aguardando o criador confirmar a conclusão.'),
    TERCEIRO: SEM_ACOES('Esta missão já foi executada e aguarda confirmação.'),
  },
  EM_DISPUTA: {
    // `resolver` é exclusivo de ADMIN e não tem tela: um botão que só um perfil pode usar, num app
    // sem área administrativa, seria 403 na cara de todo mundo.
    CRIADOR: SEM_ACOES('Missão em disputa. Um administrador vai avaliar o caso.'),
    EXECUTOR: SEM_ACOES('Missão em disputa. Um administrador vai avaliar o caso.'),
    TERCEIRO: SEM_ACOES('Esta missão está em disputa.'),
  },
  CONCLUIDA: {
    CRIADOR: SEM_ACOES('Missão concluída. A recompensa já foi creditada ao executor.'),
    EXECUTOR: SEM_ACOES('Missão concluída. A recompensa já está na sua carteira.'),
    TERCEIRO: SEM_ACOES('Esta missão já foi concluída.'),
  },
  CANCELADA: {
    CRIADOR: SEM_ACOES('Você cancelou esta missão.'),
    EXECUTOR: SEM_ACOES('Esta missão foi cancelada pelo criador.'),
    TERCEIRO: SEM_ACOES('Esta missão foi cancelada.'),
  },
  EXPIRADA: {
    CRIADOR: SEM_ACOES(
      'A janela desta missão passou sem execução. Crie outra para tentar de novo.',
    ),
    EXECUTOR: SEM_ACOES('A janela desta missão passou sem execução.'),
    TERCEIRO: SEM_ACOES('A janela desta missão passou sem execução.'),
  },
};

export function acoesDisponiveis(status: StatusMissao, papel: PapelNaMissao): EstadoDeAcoes {
  return MATRIZ[status][papel];
}

/**
 * Aplica a trava de reputação sobre um estado já calculado.
 *
 * Camada SEPARADA da `MATRIZ` de propósito. A matriz é uma função pura de (status × papel) e é
 * exaustiva por construção; o nível depende de dois valores que não estão nela — o `nivelMinimo` da
 * missão e o nível de quem olha, que vem do perfil e não da sessão. Enfiar isso na tabela obrigaria
 * cada célula a conhecer o usuário e destruiria a exaustividade que o `Record<StatusMissao, …>`
 * garante.
 *
 * Só `aceitar` é travado: é a única transição que o servidor recusa por nível.
 *
 * `nivelDoUsuario` indefinido (perfil ainda carregando) NÃO bloqueia — bloquear ali mostraria um
 * botão desabilitado que habilita sozinho um instante depois, o que parece defeito.
 */
export function comTravaDeNivel(
  estado: EstadoDeAcoes,
  nivelMinimo: number,
  nivelDoUsuario: number | undefined,
): EstadoDeAcoes {
  if (nivelMinimo <= 1 || nivelDoUsuario === undefined || nivelDoUsuario >= nivelMinimo) {
    return estado;
  }
  return {
    ...estado,
    acoes: estado.acoes.map((acao) =>
      acao.acao === 'aceitar'
        ? {
            ...acao,
            bloqueio: {
              motivo: `Esta missão exige nível ${nivelMinimo}. O seu é ${nivelDoUsuario}.`,
            },
          }
        : acao,
    ),
  };
}
