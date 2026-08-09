import { Modal, StyleSheet, Text, View } from 'react-native';

import { Botao } from './Botao';
import { cores, espaco, raio, tipografia } from '@/theme';

interface Props {
  visivel: boolean;
  titulo: string;
  mensagem: string;
  rotuloConfirmar?: string;
  rotuloCancelar?: string;
  /**
   * Inverte o PESO dos botões: confirmar vira secundário e cancelar vira primário.
   *
   * Diferenciação por hierarquia, e não por cor. Um botão vermelho dependeria de o usuário
   * enxergar vermelho, e o `Botao` não expõe override de cor de texto — inventar um só para isto
   * ampliaria a API do componente mais tocado do app por causa de um caso.
   */
  destrutivo?: boolean;
  carregando?: boolean;
  aoConfirmar: () => void;
  aoCancelar: () => void;
  testID?: string;
}

/**
 * Confirmação antes de ação irreversível: desistir, cancelar, confirmar conclusão, excluir conta.
 *
 * **Componente, e não `Alert.alert`.** O alerta nativo não aceita estado de carregamento, e a
 * ação confirmada aqui é sempre uma chamada de rede — sem spinner, a pessoa toca "Confirmar" duas
 * vezes porque nada mudou na tela. Ele também não é estilizável nem testável pela RNTL.
 *
 * **Cancelar vem primeiro na árvore e é o botão de maior peso visual.** A confirmação existe para
 * dar uma chance de recuar; o caminho de menor esforço tem de ser o recuo, não o prosseguimento.
 */
export function DialogoConfirmacao({
  visivel,
  titulo,
  mensagem,
  rotuloConfirmar = 'Confirmar',
  rotuloCancelar = 'Cancelar',
  destrutivo = false,
  carregando = false,
  aoConfirmar,
  aoCancelar,
  testID,
}: Props) {
  return (
    <Modal visible={visivel} transparent animationType="fade" onRequestClose={aoCancelar}>
      <View style={estilos.raiz}>
        {/* Véu em View própria: pôr `opacity` na raiz apagaria também o diálogo, que é filho. */}
        <View style={estilos.veu} />
        <View
          style={estilos.caixa}
          testID={testID}
          accessible
          accessibilityViewIsModal
          accessibilityRole="alert"
        >
          <Text style={estilos.titulo} accessibilityRole="header">
            {titulo}
          </Text>
          <Text style={estilos.mensagem}>{mensagem}</Text>

          <View style={estilos.acoes}>
            <Botao
              titulo={rotuloCancelar}
              variante={destrutivo ? 'primario' : 'secundario'}
              onPress={aoCancelar}
              disabled={carregando}
              estilo={estilos.botao}
              testID={testID ? `${testID}-cancelar` : undefined}
            />
            <Botao
              titulo={rotuloConfirmar}
              variante={destrutivo ? 'secundario' : 'primario'}
              onPress={aoConfirmar}
              carregando={carregando}
              estilo={estilos.botao}
              testID={testID ? `${testID}-confirmar` : undefined}
            />
          </View>
        </View>
      </View>
    </Modal>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, justifyContent: 'center', padding: espaco.xl },
  veu: { ...StyleSheet.absoluteFill, backgroundColor: cores.tinta, opacity: 0.45 },
  caixa: {
    backgroundColor: cores.branco,
    borderRadius: raio.lg,
    padding: espaco.lg,
    gap: espaco.md,
  },
  titulo: { ...tipografia.subtitulo, color: cores.tinta },
  mensagem: { ...tipografia.corpo, color: cores.tinta70 },
  acoes: { flexDirection: 'row', gap: espaco.md, marginTop: espaco.sm },
  botao: { flex: 1 },
});
