import { useRouter } from 'expo-router';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { logout } from '@/api/auth';
import { Botao } from '@/components/Botao';
import { Card } from '@/components/Card';
import { useSessao } from '@/stores/sessao';
import { cores, espaco, tipografia } from '@/theme';

/**
 * Perfil mínimo — e a limitação é do contrato, não da tela.
 *
 * `GET /auth/me` devolve hoje apenas `{id, email, papel}`. Nome, handle, XP, nível e tribo existem
 * no banco e NÃO estão no DTO (Pendência #3 do CLAUDE.md). Esta tela mostra o que a API entrega e
 * nada além: inventar um "Nível 3" a partir de dado que o app não recebeu seria mentir para o
 * usuário, e derivá-lo de XP no cliente duplicaria a `RegraNivel` do servidor — o mesmo erro que o
 * ADR 0009 fechou para a fórmula de recompensa.
 */
export default function TelaPerfil() {
  const router = useRouter();
  const usuario = useSessao((estado) => estado.usuario);
  const refreshToken = useSessao((estado) => estado.refreshToken);
  const encerrar = useSessao((estado) => estado.encerrar);

  async function sair() {
    // Avisar o servidor revoga a família de refresh; se a chamada falhar, a sessão local é limpa do
    // mesmo jeito — um logout que depende da rede deixaria o usuário preso dentro do app offline.
    if (refreshToken) {
      await logout(refreshToken).catch(() => undefined);
    }
    await encerrar();
    router.replace('/(auth)/login');
  }

  return (
    <SafeAreaView style={estilos.tela} edges={['top']}>
      <ScrollView contentContainerStyle={estilos.corpo}>
        <Text style={estilos.titulo}>Perfil</Text>

        <Card>
          <Campo rotulo="E-mail" valor={usuario?.email ?? '—'} />
          <Campo rotulo="Papel" valor={usuario?.papel === 'ADMIN' ? 'Administrador' : 'Usuário'} />
          <Campo rotulo="Identificador" valor={usuario?.id ?? '—'} />
        </Card>

        <Text style={estilos.nota}>
          Nome, tribo, XP e nível ainda não são devolvidos por{' '}
          <Text style={estilos.codigo}>GET /auth/me</Text>. Entram aqui quando o contrato os
          expuser.
        </Text>

        <Botao titulo="Sair" onPress={sair} variante="secundario" testID="botao-sair" />
      </ScrollView>
    </SafeAreaView>
  );
}

function Campo({ rotulo, valor }: { rotulo: string; valor: string }) {
  return (
    <View style={estilos.campo}>
      <Text style={estilos.rotulo}>{rotulo}</Text>
      <Text style={estilos.valor}>{valor}</Text>
    </View>
  );
}

const estilos = StyleSheet.create({
  tela: { flex: 1, backgroundColor: cores.papel },
  corpo: { padding: espaco.lg, gap: espaco.lg },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  campo: { gap: espaco.xs },
  rotulo: { ...tipografia.legenda, color: cores.tinta50 },
  valor: { ...tipografia.corpo, color: cores.tinta },
  nota: { ...tipografia.legenda, color: cores.tinta70 },
  codigo: { fontFamily: 'monospace', color: cores.tinta },
});
