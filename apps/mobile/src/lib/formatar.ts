/**
 * Formatação de distância.
 *
 * O NÚMERO vem do backend — `ST_Distance` sobre `geography`, em metros, medido pelo PostGIS. O app
 * só escolhe como escrevê-lo. Recalcular no cliente (Haversine e afins) daria um segundo valor,
 * quase igual e ocasionalmente diferente, e a pergunta "por que a lista diz 180 m e o check-in diz
 * que estou a 210 m?" não teria resposta boa. Ver ADR 0007 e a regra de validação no servidor.
 */
export function formatarDistancia(metros: number): string {
  if (!Number.isFinite(metros) || metros < 0) return '—';
  if (metros < 1000) return `${Math.round(metros)} m`;
  const km = metros / 1000;
  // Uma casa até 10 km, nenhuma acima: "12,4 km" a pé não informa mais que "12 km".
  return km < 10 ? `${km.toFixed(1).replace('.', ',')} km` : `${Math.round(km)} km`;
}

const ROTULOS_CATEGORIA = {
  ENTREGA: 'Entrega',
  COLETA: 'Coleta',
  TRIBO: 'Tribo',
  AJUDA: 'Ajuda',
} as const;

export function rotuloCategoria(categoria: keyof typeof ROTULOS_CATEGORIA): string {
  return ROTULOS_CATEGORIA[categoria];
}

const ROTULOS_STATUS = {
  RASCUNHO: 'Rascunho',
  ABERTA: 'Aberta',
  ACEITA: 'Aceita',
  EM_ANDAMENTO: 'Em andamento',
  AGUARDANDO_CONFIRMACAO: 'Aguardando confirmação',
  EM_DISPUTA: 'Em disputa',
  CONCLUIDA: 'Concluída',
  CANCELADA: 'Cancelada',
  EXPIRADA: 'Expirada',
} as const;

export function rotuloStatus(status: keyof typeof ROTULOS_STATUS): string {
  return ROTULOS_STATUS[status];
}

/** Data curta para janela de missão e extrato. */
export function formatarDataHora(iso: string): string {
  const data = new Date(iso);
  if (Number.isNaN(data.getTime())) return '—';
  return data.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Quanto falta até a janela da missão fechar — em texto que se lê em voz alta.
 *
 * <b>Relativo, e não absoluto.</b> `formatarDataHora` já existe e devolve "24/08 18:30", que é o
 * certo para um extrato: ali interessa QUANDO aconteceu. Aqui interessa outra coisa — se dá tempo de
 * ir. Ouvir "termina em 40 minutos" responde a pergunta; ouvir "24/08 18:30" obriga a pessoa a
 * calcular de cabeça, e mais ainda se ela está andando na rua.
 *
 * Granularidade decrescente de propósito: minutos enquanto a decisão é urgente, horas depois, dias
 * quando falta muito. "termina em 2.847 minutos" é tecnicamente exato e inútil.
 *
 * @param agora injetável para o teste não depender do relógio da máquina
 */
export function formatarPrazo(janelaFimIso: string, agora: Date = new Date()): string {
  const fim = new Date(janelaFimIso);
  if (Number.isNaN(fim.getTime())) return '—';

  const minutos = Math.round((fim.getTime() - agora.getTime()) / 60000);

  // Passado é "encerrada", não "termina em -30 minutos". O radar só devolve missão aberta, mas a
  // tela pode estar parada há um tempo — e um número negativo lido em voz alta não significa nada.
  if (minutos <= 0) return 'encerrada';
  if (minutos < 60) return `termina em ${minutos} min`;

  const horas = Math.round(minutos / 60);
  if (horas < 24) return `termina em ${horas} h`;

  const dias = Math.round(horas / 24);
  return dias === 1 ? 'termina em 1 dia' : `termina em ${dias} dias`;
}
