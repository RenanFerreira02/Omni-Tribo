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
        // `verdePrimario` sobre branco dá 3,39:1 — reprova em AA para os 12 px do rótulo da aba.
        // É o MESMO número que o javadoc de `coresStatus` já registrou ao mover o status CONCLUIDA
        // para `verdeEscuro`; a correção tinha sido aplicada ao caso descoberto e não à causa.
        tabBarActiveTintColor: cores.verdeEscuro,
        tabBarInactiveTintColor: textoAcessivel.suave,
        tabBarStyle: { backgroundColor: cores.branco, borderTopColor: cores.linha },
        // SEM teto de escala aqui, e não por esquecimento: `tabBarLabelStyle` é um `TextStyle`, não
        // as props de um `<Text>`, então `maxFontSizeMultiplier` não é expressável. Impô-lo exigiria
        // substituir o renderizador do rótulo por um `tabBarLabel` próprio — e junto iria a fiação
        // de acessibilidade que o React Navigation dá de graça (papel de aba, estado selecionado,
        // "1 de 5"). Trocar semântica por um teto de fonte seria o oposto do objetivo desta fase.
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
          // O BADGE NÃO CHEGA AO LEITOR DE TELA. O React Navigation o desenha como um nó próprio e
          // não o inclui no rótulo da aba — a contagem de não lidos, que é a única informação nova
          // da barra, era puramente visual. O rótulo da aba passa a dizê-la.
          tabBarAccessibilityLabel:
            naoLidos && naoLidos > 0
              ? `Avisos, ${naoLidos} não ${naoLidos === 1 ? 'lido' : 'lidos'}`
              : 'Avisos',
          // `coral` como fundo do badge com texto branco dá 3,87:1, abaixo dos 4,5:1 de AA para
          // texto pequeno. `verdeEscuro` dá 6,2:1 com o mesmo branco, e é o token que o projeto já
          // usa quando precisa de fundo sólido legível (ver o javadoc de `coresStatus`).
          tabBarBadgeStyle: { backgroundColor: cores.verdeEscuro },
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
