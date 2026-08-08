import { Redirect, Tabs } from 'expo-router';
import { Text, type ColorValue } from 'react-native';

import { useSessao } from '@/stores/sessao';
import { cores, tipografia } from '@/theme';

export default function LayoutTabs() {
  const accessToken = useSessao((estado) => estado.accessToken);

  // Proteção da área logada. `<Redirect>` durante a renderização, não em efeito: a tela protegida
  // nunca chega a montar, então nenhuma query autenticada dispara sem token.
  if (!accessToken) return <Redirect href="/(auth)/login" />;

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: cores.verdePrimario,
        tabBarInactiveTintColor: cores.tinta50,
        tabBarStyle: { backgroundColor: cores.branco, borderTopColor: cores.linha },
        tabBarLabelStyle: tipografia.legenda,
      }}
    >
      <Tabs.Screen
        name="index"
        options={{ title: 'Missões', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◎" /> }}
      />
      <Tabs.Screen
        name="carteira"
        options={{ title: 'Carteira', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◈" /> }}
      />
      <Tabs.Screen
        name="perfil"
        options={{ title: 'Perfil', tabBarIcon: ({ color }) => <Icone cor={color} glifo="◐" /> }}
      />
    </Tabs>
  );
}

function Icone({ cor, glifo }: { cor: ColorValue; glifo: string }) {
  return <Text style={{ color: cor, fontSize: 20 }}>{glifo}</Text>;
}
