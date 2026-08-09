import { Redirect, Stack } from 'expo-router';

import { useSessao } from '@/stores/sessao';

export default function LayoutAuth() {
  const accessToken = useSessao((estado) => estado.accessToken);

  // Já logado não vê login: evita que o botão "voltar" do Android traga a tela de credenciais de
  // volta por cima da sessão ativa.
  if (accessToken) return <Redirect href="/(tabs)" />;

  return <Stack screenOptions={{ headerShown: false }} />;
}
