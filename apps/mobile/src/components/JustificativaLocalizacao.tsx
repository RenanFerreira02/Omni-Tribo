import { StyleSheet, Text, View } from 'react-native';

import { Botao } from './Botao';
import { Card } from './Card';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

interface Props {
  /** O que essa tela faz com a posição. A frase muda; a política de privacidade abaixo, não. */
  proposito: string;
  aoPermitir: () => void;
  /** O que continua funcionando sem a permissão. Recusar precisa ter uma saída visível. */
  semPermissao: string;
  testID?: string;
}

/**
 * Justificativa EXIBIDA ANTES do diálogo do sistema.
 *
 * <b>Por que isto é um componente compartilhado, e não texto duplicado em cada tela.</b> O diálogo
 * nativo de permissão é de uma via só: negado uma vez, o Android não o mostra de novo e a pessoa
 * precisa ir às configurações. Existe UMA chance de explicar, e ela pertence à primeira tela que
 * pedir — que nem sempre é a que você imagina.
 *
 * Foi exatamente esse o defeito que uma auditoria encontrou: a aba de missões montava com
 * `useLocalizacao()` e gastava o prompt, enquanto o card caprichado do mapa só aparecia depois. O
 * texto existia e nunca era o primeiro a falar. Com um componente só, acrescentar uma tela que
 * precise de posição não recria o problema — ela reusa a mesma porta.
 */
export function JustificativaLocalizacao({ proposito, aoPermitir, semPermissao, testID }: Props) {
  return (
    <View style={estilos.raiz} testID={testID}>
      <Card>
        <Text style={estilos.titulo} accessibilityRole="header">
          Ver missões perto de você
        </Text>
        <Text style={estilos.texto}>{proposito}</Text>
        <Text style={estilos.texto}>
          A posição exata é usada só no momento do check-in, para confirmar que você chegou ao local
          — e nunca é compartilhada com outros usuários.
        </Text>
        <Text style={estilos.ressalva}>{semPermissao}</Text>
        <Botao titulo="Permitir localização" onPress={aoPermitir} testID="botao-permitir" />
      </Card>
    </View>
  );
}

const estilos = StyleSheet.create({
  raiz: { padding: espaco.lg, justifyContent: 'center', flexGrow: 1 },
  titulo: { ...tipografia.subtitulo, color: cores.tinta },
  texto: { ...tipografia.corpo, color: cores.tinta70 },
  ressalva: { ...tipografia.legenda, color: textoAcessivel.suave },
});
