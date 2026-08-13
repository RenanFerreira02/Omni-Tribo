import { Redirect } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { consumirRotaPendente } from '@/features/navegacao/useDeepLink';
import { jaViuOnboarding } from '@/features/onboarding/visto';
import { useSessao } from '@/stores/sessao';
import { cores } from '@/theme';

/**
 * Porta de entrada: decide entre onboarding, (auth) e (tabs).
 *
 * A decisão vive numa rota própria, e não num `useEffect` com `router.replace` dentro dos layouts,
 * porque `<Redirect>` acontece durante a renderização — antes de a tela protegida montar e disparar
 * as queries dela. Redirecionar depois do mount deixaria um piscar da tela de missões (e um GET
 * autenticado sem token) para quem não está logado.
 *
 * O onboarding exige uma LEITURA ASSÍNCRONA do armazenamento, então aqui há um estado de espera que
 * a versão anterior não precisava ter. Enquanto ele dura, nada é redirecionado: mandar para o login
 * e corrigir depois faria a tela piscar duas vezes para todo usuário novo.
 *
 * Quem já tem sessão NUNCA vê o onboarding, mesmo sem tê-lo visto antes — reinstalar o app com a
 * sessão restaurada é o caso, e três slides de boas-vindas para quem já usa o produto é ruído.
 */
export default function Entrada() {
  const accessToken = useSessao((estado) => estado.accessToken);
  const [precisaOnboarding, setPrecisaOnboarding] = useState<boolean | null>(null);

  useEffect(() => {
    let ativo = true;
    void jaViuOnboarding().then((visto) => {
      if (ativo) setPrecisaOnboarding(!visto);
    });
    return () => {
      ativo = false;
    };
  }, []);

  // Deep link recebido antes de haver sessão: agora que ela existe, honra a intenção original em vez
  // de largar a pessoa na tela de missões sem explicação. Consome uma vez só — um link guardado
  // indefinidamente levaria alguém a uma tela inesperada dias depois.
  if (accessToken) {
    const pendente = consumirRotaPendente();
    return <Redirect href={pendente ?? '/(tabs)'} />;
  }

  if (precisaOnboarding === null) {
    return (
      <View style={estilos.espera} testID="entrada-carregando">
        <ActivityIndicator color={cores.verdePrimario} />
      </View>
    );
  }

  return <Redirect href={precisaOnboarding ? '/onboarding' : '/(auth)/login'} />;
}

const estilos = StyleSheet.create({
  espera: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: cores.papel },
});
