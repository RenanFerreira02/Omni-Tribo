import { useEffect } from 'react';
import { AccessibilityInfo } from 'react-native';

/**
 * Fala um desfecho para o leitor de tela.
 *
 * **Por que isto existe, e por que `accessibilityLiveRegion` não bastava.** O `Aviso` já é uma
 * região viva, então a FALHA de quase toda operação era anunciada. O SUCESSO não era: o app trocava
 * o estado da tela e seguia em silêncio. Quem usa leitor de tela tocava "Fazer check-in", ouvia o
 * botão sumir e não recebia confirmação de que a recompensa havia sido creditada — o inventário de
 * acessibilidade registrou isso como o achado mais caro da aplicação.
 *
 * E há um segundo motivo, que vale mesmo onde já existe `Aviso`: **`accessibilityLiveRegion` é uma
 * prop de ANDROID**. No iOS ela não faz nada. `announceForAccessibility` funciona nos dois.
 *
 * O molde é o `useEffect` que a tela de benefícios já usava para anunciar o código de retirada; o
 * que este hook faz é tirá-lo de lá e torná-lo a regra da casa.
 *
 * @param mensagem o que dizer, ou `null` quando não há nada a anunciar. Anuncia SÓ quando o texto
 *     muda — uma re-renderização com a mesma mensagem não repete a fala, senão qualquer atualização
 *     de estado viraria eco.
 */
export function useAnuncio(mensagem: string | null | undefined): void {
  useEffect(() => {
    if (mensagem) {
      AccessibilityInfo.announceForAccessibility(mensagem);
    }
  }, [mensagem]);
}

/**
 * "180 m" vira "180 metros".
 *
 * Frase escrita e frase falada divergem aqui de propósito. Na tela, "m" é compacto e universal; em
 * voz, os motores de TTS tratam abreviação de unidade de forma inconsistente — uns leem "metros",
 * outros soletram "eme", outros pulam. Numa instrução acionável do tipo "aproxime-se para até 50 m"
 * o número sem unidade não orienta ninguém.
 */
export function paraFala(texto: string): string {
  return texto
    .replace(/(\d)\s*m\b/g, '$1 metros')
    .replace(/\b1 metros\b/g, '1 metro')
    .replace(/(\d)\s*km\b/g, '$1 quilômetros');
}
