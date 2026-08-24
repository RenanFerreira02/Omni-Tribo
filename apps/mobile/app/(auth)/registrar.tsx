import { Link, useRouter } from 'expo-router';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { useRegistro } from '@/features/auth/hooks';
import { errosDoZod, errosPorCampo, mensagemDoErro } from '@/lib/formulario';
import { registroSchema } from '@/schemas';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

export default function Registrar() {
  const router = useRouter();
  const criar = useRegistro();

  const [nome, setNome] = useState('');
  const [email, setEmail] = useState('');
  const [handle, setHandle] = useState('');
  const [senha, setSenha] = useState('');
  const [errosLocais, setErrosLocais] = useState<Record<string, string>>({});

  const erros = { ...errosPorCampo(criar.error), ...errosLocais };
  const aviso = mensagemDoErro(criar.error);

  function submeter() {
    const analise = registroSchema.safeParse({
      nome: nome.trim(),
      email: email.trim(),
      handle: handle.trim(),
      senha,
    });
    if (!analise.success) {
      // `errosDoZod`, e não `Object.fromEntries`: o utilitário guarda a PRIMEIRA mensagem por
      // campo, e a expressão copiada guardava a última. Duas telas mostrando mensagens diferentes
      // para o mesmo campo com duas regras violadas — e a função existia justamente para isso.
      setErrosLocais(errosDoZod(analise.error));
      return;
    }
    setErrosLocais({});
    // triboId não é enviado. `GET /tribos` JÁ EXISTE e responde 200 — o comentário anterior dizia
    // o contrário e ficou obsoleto quando o endpoint foi criado. O que falta é a decisão de
    // produto: escolher tribo no cadastro, ou depois, no perfil. Enquanto isso o campo continua
    // opcional no backend e o registro não o envia.
    criar.mutate(analise.data, { onSuccess: () => router.replace('/(tabs)') });
  }

  return (
    <SafeAreaView style={estilos.tela}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={estilos.flex}
      >
        <ScrollView contentContainerStyle={estilos.conteudo} keyboardShouldPersistTaps="handled">
          <Text style={estilos.titulo}>Criar conta</Text>

          <View style={estilos.formulario}>
            <CampoTexto
              rotulo="Nome"
              value={nome}
              onChangeText={setNome}
              erro={erros.nome}
              testID="campo-nome"
            />
            <CampoTexto
              rotulo="E-mail"
              value={email}
              onChangeText={setEmail}
              erro={erros.email}
              autoCapitalize="none"
              keyboardType="email-address"
              testID="campo-email"
            />
            <CampoTexto
              rotulo="@ do perfil"
              value={handle}
              onChangeText={setHandle}
              erro={erros.handle}
              autoCapitalize="none"
              testID="campo-handle"
            />
            <CampoTexto
              rotulo="Senha (mínimo 12 caracteres)"
              value={senha}
              onChangeText={setSenha}
              erro={erros.senha}
              secureTextEntry
              testID="campo-senha"
            />

            {aviso ? (
              <Text
                style={estilos.aviso}
                testID="erro-registro"
                accessibilityRole="alert"
                accessibilityLiveRegion="polite"
              >
                {aviso}
              </Text>
            ) : null}

            <Botao
              titulo="Criar conta"
              onPress={submeter}
              carregando={criar.isPending}
              testID="botao-criar"
            />
          </View>

          <View style={estilos.rodape}>
            <Text style={estilos.rodapeTexto}>Já tem conta?</Text>
            {/*
              O alvo cresce por PADDING, no estilo, e não por `hitSlop`: o `Link` do expo-router
              tipa só `LinkProps` e não repassa `hitSlop`. Ele é um `<Text>` de 22 pt de lineHeight
              — metade do mínimo de 44 da WCAG 2.5.5 — e é o único caminho entre login e cadastro.
              O papel de link já vem do próprio componente (`useLinkToPathProps` define
              `role: 'link'`); o que faltava era o alvo.
            */}
            <Link href="/(auth)/login" style={estilos.link}>
              Entrar
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
  conteudo: { flexGrow: 1, justifyContent: 'center', padding: espaco.xl, gap: espaco.xl },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  formulario: { gap: espaco.lg },
  aviso: { ...tipografia.corpo, color: textoAcessivel.coral },
  rodape: { flexDirection: 'row', justifyContent: 'center', gap: espaco.xs },
  rodapeTexto: { ...tipografia.corpo, color: cores.tinta70 },
  // 11 + 22 de lineHeight + 11 = 44 pt de alvo. Ver o comentário no JSX.
  link: {
    ...tipografia.corpo,
    color: cores.verdeEscuro,
    fontWeight: '600',
    paddingVertical: 11,
  },
});
