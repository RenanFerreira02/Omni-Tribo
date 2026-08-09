import { Link, useRouter } from 'expo-router';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { useLogin } from '@/features/auth/hooks';
import { errosPorCampo, mensagemDoErro } from '@/lib/formulario';
import { loginSchema } from '@/schemas';
import { cores, espaco, tipografia } from '@/theme';

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
      setErrosLocais(
        Object.fromEntries(
          analise.error.issues.map((problema) => [String(problema.path[0]), problema.message]),
        ),
      );
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
              <Text style={estilos.aviso} testID="erro-login">
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
  marca: { fontSize: 32, lineHeight: 38, fontWeight: '700', color: cores.verdeEscuro },
  subtitulo: { ...tipografia.corpo, color: cores.tinta70 },
  formulario: { gap: espaco.lg },
  aviso: { ...tipografia.corpo, color: cores.coral },
  rodape: { flexDirection: 'row', justifyContent: 'center', gap: espaco.xs },
  rodapeTexto: { ...tipografia.corpo, color: cores.tinta70 },
  link: { ...tipografia.corpo, color: cores.verdePrimario, fontWeight: '600' },
});
