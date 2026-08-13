import { Redirect, Tabs } from 'expo-router';
import { Text, type ColorValue } from 'react-native';

import { useContagemNaoLidos } from '@/features/alertas/hooks';
import { useSessao } from '@/stores/sessao';
import { cores, textoAcessivel, tipografia } from '@/theme';

export default function LayoutTabs() {
  const accessToken = useSessao((estado) => estado.accessToken);
  // Antes do early return: a ordem dos hooks precisa ser estável entre renderizações. O hook tem
  // `enabled` implícito pela própria sessão — sem token, a query falha com 401 e o badge fica
  // vazio, que é exatamente o que a tela de login deve mostrar.
  const { data: naoLidos } = useContagemNaoLidos();

  // Proteção da área logada. `<Redirect>` durante a renderização, não em efeito: a tela protegida
  // nunca chega a montar, então nenhuma query autenticada dispara sem token.
  if (!accessToken) return <Redirect href="/(auth)/login" />;

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: cores.verdePrimario,
        tabBarInactiveTintColor: textoAcessivel.suave,
        tabBarStyle: { backgroundColor: cores.branco, borderTopColor: cores.linha },
        tabBarLabelStyle: tipografia.legenda,
      }}
    >
      <Tabs.Screen
        name="index"
        options={{ title: 'Missões', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◎" /> }}
      />
      <Tabs.Screen
        name="mapa"
        options={{ title: 'Mapa', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◍" /> }}
      />
      <Tabs.Screen
        name="carteira"
        options={{ title: 'Carteira', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◈" /> }}
      />
      <Tabs.Screen
        name="notificacoes"
        options={{
          title: 'Avisos',
          tabBarIcon: ({ color }) => <Icone cor={color} glifo="◔" />,
          // O contador vem de uma query própria e barata (`/alertas/nao-lidos/contagem`), que não
          // traz corpo de notificação nenhum. `undefined` esconde o badge — 0 renderizaria uma
          // bolinha com "0" dentro.
          tabBarBadge: naoLidos && naoLidos > 0 ? naoLidos : undefined,
          tabBarBadgeStyle: { backgroundColor: cores.coral },
        }}
      />
      <Tabs.Screen
        name="perfil"
        options={{ title: 'Perfil', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◐" /> }}
      />
    </Tabs>
  );
}

function Icone({ cor, glifo }: { cor: ColorValue; glifo: string }) {
  return (
    // Decorativo: o TÍTULO da aba já diz o que ela é, e o leitor de tela anunciando "círculo com
    // ponto, Missões" só atrapalha. O app já esconde glifo decorativo em `IndicadorPaginas` e no
    // onboarding — aqui tinha ficado de fora.
    <Text
      style={{ color: cor, fontSize: tipografia.icone.fontSize }}
      accessibilityElementsHidden
      importantForAccessibility="no"
    >
      {glifo}
    </Text>
  );
}
