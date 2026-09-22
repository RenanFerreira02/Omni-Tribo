import { useRouter } from 'expo-router';
import { StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { TituloTela } from '@/components/TituloTela';
import { FormularioMissao, paraRequest } from '@/features/missoes/FormularioMissao';
import { useCriarMissao } from '@/features/missoes/hooks';
import type { CriarMissaoForm } from '@/schemas';
import { cores } from '@/theme';

/**
 * Criação de missão.
 *
 * O formulário inteiro vive em `FormularioMissao`, compartilhado com a tela de edição de rascunho.
 * Duplicá-lo era o caminho curto e o errado: as duas telas têm as MESMAS seis regras cruzadas, e a
 * primeira divergência entre elas apareceria como um 400 que só acontece num dos dois caminhos.
 */
export default function CriarMissao() {
  const router = useRouter();
  const criar = useCriarMissao();

  function enviar(dados: CriarMissaoForm) {
    criar.mutate(paraRequest(dados), {
      onSuccess: (missao) => router.replace(`/missao/${missao.id}`),
    });
  }

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-criar-missao">
      <TituloTela>Nova missão</TituloTela>
      <FormularioMissao
        modo="criar"
        rotuloEnvio="Criar missão"
        enviando={criar.isPending}
        erro={criar.error}
        aoEnviar={enviar}
        nota="A missão nasce como rascunho. Publicar é o próximo passo, no detalhe dela — e você
          encontra os rascunhos de volta pela aba Missões."
        testID="botao-criar"
      />
    </SafeAreaView>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
});
