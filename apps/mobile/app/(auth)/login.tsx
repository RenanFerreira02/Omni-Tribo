import { Link, useRouter } from 'expo-router';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { useLogin } from '@/features/auth/hooks';
import { errosDoZod, errosPorCampo, mensagemDoErro } from '@/lib/formulario';
import { loginSchema } from '@/schemas';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

export default function Login() {
  const router = useRouter();
  const entrar = useLogin();

  const [email, setEmail] = useState('');
  const [senha, setSenha] = useState('');
  const [errosLocais, setErrosLocais] = useState<Record<string, string>>({});

  const errosDoServidor = errosPorCampo(entrar.error);
  const erros = { ...errosDoServidor, ...errosLocais };
  const aviso = mensagemDoErro(entrar.error);

  async function submeter() {
    const analise = loginSchema.safeParse({ email: email.trim(), senha });
    if (!analise.success) {
      // `errosDoZod`, e não `Object.fromEntries`: o utilitário guarda a PRIMEIRA mensagem por
      // campo, e a expressão copiada guardava a última. Duas telas mostrando mensagens diferentes
      // para o mesmo campo com duas regras violadas — e a função existia justamente para isso.
      setErrosLocais(errosDoZod(analise.error));
      return;
    }
    setErrosLocais({});
    entrar.mutate(analise.data, { onSuccess: () => router.replace('/(tabs)') });
  }

  return (
    <SafeAreaView style={estilos.tela}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={estilos.flex}
      >
        <ScrollView contentContainerStyle={estilos.conteudo} keyboardShouldPersistTaps="handled">
          <View style={estilos.cabecalho}>
            <Text style={estilos.marca}>Omni-Tribo</Text>
            <Text style={estilos.subtitulo}>Missões do seu bairro, feitas por quem mora nele.</Text>
          </View>

          <View style={estilos.formulario}>
            <CampoTexto
              rotulo="E-mail"
              value={email}
              onChangeText={setEmail}
              erro={erros.email}
              autoCapitalize="none"
              autoComplete="email"
              keyboardType="email-address"
              placeholder="voce@exemplo.com"
              testID="campo-email"
            />
            <CampoTexto
              rotulo="Senha"
              value={senha}
              onChangeText={setSenha}
              erro={erros.senha}
              secureTextEntry
              autoComplete="current-password"
              testID="campo-senha"
            />

            {aviso ? (
              <Text
                style={estilos.aviso}
                testID="erro-login"
                // Falha de login era um `<Text>` mudo: aparecia na tela e não era anunciada. É o
                // primeiro erro que um usuário novo encontra, e o que ele precisa saber é POR QUE
                // não entrou.
                accessibilityRole="alert"
                accessibilityLiveRegion="polite"
              >
                {aviso}
              </Text>
            ) : null}

            <Botao
              titulo="Entrar"
              onPress={submeter}
              carregando={entrar.isPending}
              testID="botao-entrar"
            />
          </View>

          <View style={estilos.rodape}>
            <Text style={estilos.rodapeTexto}>Ainda não tem conta?</Text>
            {/*
              O alvo cresce por PADDING, no estilo, e não por `hitSlop`: o `Link` do expo-router
              tipa só `LinkProps` e não repassa `hitSlop`. Ele é um `<Text>` de 22 pt de lineHeight
              — metade do mínimo de 44 da WCAG 2.5.5 — e é o único caminho entre login e cadastro.
              O papel de link já vem do próprio componente (`useLinkToPathProps` define
              `role: 'link'`); o que faltava era o alvo.
            */}
            <Link href="/(auth)/registrar" style={estilos.link}>
              Criar conta
            </Link>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const estilos = StyleSheet.create({
  tela: { flex: 1, backgroundColor: cores.papel },
  flex: { flex: 1 },
  conteudo: { flexGrow: 1, justifyContent: 'center', padding: espaco.xl, gap: espaco.xxl },
  cabecalho: { gap: espaco.sm },
  marca: { ...tipografia.display, color: cores.verdeEscuro },
  subtitulo: { ...tipografia.corpo, color: cores.tinta70 },
  formulario: { gap: espaco.lg },
  aviso: { ...tipografia.corpo, color: textoAcessivel.coral },
  rodape: { flexDirection: 'row', justifyContent: 'center', gap: espaco.xs },
  rodapeTexto: { ...tipografia.corpo, color: cores.tinta70 },
  // 11 + 22 de lineHeight + 11 = 44 pt de alvo. Ver o comentário no JSX.
  link: {
    ...tipografia.corpo,
    color: cores.verdeEscuro,
    fontWeight: '600',
    // `espaco.md` (12), e não um 11 solto: 12 + 22 de lineHeight + 12 = 46, acima do mínimo de
    // `alvo.minimo`. O 11 anterior mirava 44 exato e era um número fora de qualquer escala — o que
    // ele guardava, e o comentário acima diz, é uma regra de ALVO, não de respiro.
    paddingVertical: espaco.md,
  },
});
