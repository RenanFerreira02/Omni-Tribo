import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, Share, StyleSheet, Switch, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { logout } from '@/api/auth';
import { mensagemDe } from '@/api/erros';
import type { ConquistaResponse, TipoConsentimento } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { BarraProgresso } from '@/components/BarraProgresso';
import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { Card } from '@/components/Card';
import { DialogoConfirmacao } from '@/components/DialogoConfirmacao';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { FolhaInferior } from '@/components/FolhaInferior';
import {
  useConsentimentos,
  useDefinirConsentimento,
  useExcluirConta,
  useExportarDados,
  usePerfil,
} from '@/features/perfil/hooks';
import { useSessao } from '@/stores/sessao';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

const ROTULO_CONSENTIMENTO: Record<TipoConsentimento, { titulo: string; descricao: string }> = {
  LOCALIZACAO: {
    titulo: 'Localização',
    descricao: 'Usar sua posição para mostrar missões próximas e validar o check-in.',
  },
  NOTIFICACAO: {
    titulo: 'Notificações',
    descricao: 'Avisar sobre recompensas creditadas e missões novas no seu bairro.',
  },
  TERMOS: {
    titulo: 'Termos de uso',
    descricao: 'Aceite dos termos e da política de privacidade da plataforma.',
  },
};

export default function TelaPerfil() {
  const router = useRouter();
  const encerrar = useSessao((estado) => estado.encerrar);
  const refreshToken = useSessao((estado) => estado.refreshToken);

  const perfil = usePerfil();
  const consentimentos = useConsentimentos();
  const definirConsentimento = useDefinirConsentimento();
  const exportar = useExportarDados();
  const excluir = useExcluirConta();

  const [privacidadeAberta, setPrivacidadeAberta] = useState(false);
  const [confirmandoExclusao, setConfirmandoExclusao] = useState(false);
  const [senhaAberta, setSenhaAberta] = useState(false);
  const [senha, setSenha] = useState('');

  async function sair() {
    if (refreshToken) await logout(refreshToken).catch(() => undefined);
    await encerrar();
    router.replace('/(auth)/login');
  }

  async function compartilharDados() {
    const dados = await exportar.mutateAsync();
    // `Share` do core, e não gravação em arquivo: exportar é um direito do titular, e o destino é
    // decisão DELE — e-mail, nuvem, outro app. Salvar num diretório do aplicativo devolveria o dado
    // para dentro da mesma caixa de onde ele quer tirá-lo.
    await Share.share({
      title: 'Meus dados no Omni-Tribo',
      message: JSON.stringify(dados, null, 2),
    });
  }

  function confirmarExclusao() {
    excluir.mutate(
      { senha },
      {
        onSuccess: async () => {
          await encerrar();
          router.replace('/(auth)/login');
        },
      },
    );
  }

  if (perfil.isLoading) {
    return (
      <SafeAreaView style={estilos.raiz} edges={['top']}>
        <View style={estilos.conteudo} testID="perfil-carregando">
          <Esqueleto altura={32} />
          <Esqueleto altura={100} />
          <Esqueleto altura={140} />
        </View>
      </SafeAreaView>
    );
  }

  if (perfil.error || !perfil.data) {
    return (
      <SafeAreaView style={estilos.raiz} edges={['top']}>
        <EstadoVazio
          titulo="Não foi possível carregar seu perfil"
          descricao={perfil.error ? mensagemDe(perfil.error) : undefined}
          acao={{ rotulo: 'Tentar de novo', onPress: () => void perfil.refetch() }}
          testID="perfil-erro"
        />
      </SafeAreaView>
    );
  }

  const p = perfil.data;
  const noNivel = p.xp - p.xpNivelAtual;
  const paraSubir = Math.max(p.xpProximoNivel - p.xpNivelAtual, 1);

  return (
    <SafeAreaView style={estilos.raiz} edges={['top']}>
      <ScrollView contentContainerStyle={estilos.conteudo}>
        <Text style={estilos.titulo} accessibilityRole="header">
          {p.nome}
        </Text>
        <Text style={estilos.handle}>@{p.handle}</Text>

        {/* ─── Progressão ────────────────────────────────────────────────────────────────── */}
        <Card>
          <View style={estilos.linhaNivel}>
            <Text style={estilos.nivel} testID="nivel">
              Nível {p.nivel}
            </Text>
            <Text style={estilos.xp} testID="xp">
              {p.xp} XP
            </Text>
          </View>
          <BarraProgresso
            valor={noNivel}
            meta={paraSubir}
            rotuloAcessivel={`${noNivel} de ${paraSubir} XP para o nível ${p.nivel + 1}`}
            testID="barra-xp"
          />
          <Text style={estilos.legenda}>
            Faltam {Math.max(p.xpProximoNivel - p.xp, 0)} XP para o nível {p.nivel + 1}
          </Text>
        </Card>

        {/* ─── Tribo ─────────────────────────────────────────────────────────────────────── */}
        <Card>
          <Text style={estilos.rotulo}>Sua tribo</Text>
          {p.tribo ? (
            <>
              <Text style={estilos.valor} testID="tribo-nome">
                {p.tribo.nome}
              </Text>
              <Text style={estilos.legenda}>{p.tribo.bairro}</Text>
            </>
          ) : (
            <Text style={estilos.legenda} testID="sem-tribo">
              Você ainda não faz parte de uma tribo. Tokens só são transferidos entre membros da
              mesma tribo.
            </Text>
          )}
        </Card>

        {/* ─── Conquistas ────────────────────────────────────────────────────────────────── */}
        <Text style={estilos.subtitulo}>Conquistas</Text>
        {p.conquistas.map((conquista) => (
          <Conquista key={conquista.codigo} conquista={conquista} />
        ))}

        {/* ─── Privacidade ───────────────────────────────────────────────────────────────── */}
        <Text style={estilos.subtitulo}>Privacidade e dados</Text>
        <Botao
          titulo="Gerenciar meus dados"
          variante="secundario"
          onPress={() => setPrivacidadeAberta(true)}
          testID="botao-privacidade"
        />

        <Card>
          <Text style={estilos.rotulo}>Conta</Text>
          <Text style={estilos.valor}>{p.email}</Text>
          <Text style={estilos.legenda}>
            {p.papel === 'ADMIN' ? 'Administrador' : 'Usuário'} · id {p.id}
          </Text>
        </Card>

        <Botao titulo="Sair" variante="secundario" onPress={sair} testID="botao-sair" />
      </ScrollView>

      {/* ─── Folha de privacidade ────────────────────────────────────────────────────────── */}
      <FolhaInferior
        visivel={privacidadeAberta}
        aoFechar={() => setPrivacidadeAberta(false)}
        titulo="Privacidade e dados"
        testID="folha-privacidade"
      >
        <ScrollView contentContainerStyle={estilos.folha}>
          <Text style={estilos.rotulo}>Consentimentos</Text>
          {(consentimentos.data ?? []).map((item) => (
            <View key={item.tipo} style={estilos.consentimento}>
              <View style={estilos.consentimentoTexto}>
                <Text style={estilos.valor}>{ROTULO_CONSENTIMENTO[item.tipo].titulo}</Text>
                <Text style={estilos.legenda}>{ROTULO_CONSENTIMENTO[item.tipo].descricao}</Text>
              </View>
              <Switch
                value={item.concedido}
                onValueChange={(concedido) =>
                  definirConsentimento.mutate({ tipo: item.tipo, concedido })
                }
                disabled={definirConsentimento.isPending}
                accessibilityLabel={ROTULO_CONSENTIMENTO[item.tipo].titulo}
                trackColor={{ true: cores.verdePrimario, false: cores.linha }}
                testID={`consentimento-${item.tipo}`}
              />
            </View>
          ))}

          <Text style={estilos.rotulo}>Seus dados</Text>
          <Botao
            titulo="Exportar meus dados"
            variante="secundario"
            carregando={exportar.isPending}
            onPress={() => void compartilharDados()}
            testID="botao-exportar"
          />
          <Text style={estilos.legenda}>
            Um arquivo com tudo que a plataforma guarda sobre você: cadastro, consentimentos,
            missões, lançamentos e check-ins. Sem senha nem chaves de acesso.
          </Text>

          <Text style={estilos.rotulo}>Excluir conta</Text>
          <Botao
            titulo="Excluir minha conta"
            variante="texto"
            onPress={() => setConfirmandoExclusao(true)}
            testID="botao-excluir-conta"
          />
          <Text style={estilos.legenda}>
            Seu nome, e-mail e @ são apagados e a conta é encerrada. O histórico contábil das
            missões permanece sem qualquer ligação com você — é o que a lei exige guardar.
          </Text>

          {excluir.error ? (
            <Aviso tom="erro" mensagem={mensagemDe(excluir.error)} testID="erro-exclusao" />
          ) : null}
        </ScrollView>
      </FolhaInferior>

      {/* ─── DUPLA confirmação: primeiro o aviso, depois a senha ─────────────────────────── */}
      <DialogoConfirmacao
        visivel={confirmandoExclusao}
        titulo="Excluir sua conta?"
        mensagem="Esta ação não tem volta. Você perde acesso ao histórico, às conquistas e aos tokens que ainda tiver na carteira."
        rotuloConfirmar="Continuar"
        rotuloCancelar="Cancelar"
        destrutivo
        aoConfirmar={() => {
          setConfirmandoExclusao(false);
          setSenhaAberta(true);
        }}
        aoCancelar={() => setConfirmandoExclusao(false)}
        testID="dialogo-excluir"
      />

      <FolhaInferior
        visivel={senhaAberta}
        aoFechar={() => setSenhaAberta(false)}
        titulo="Confirme com sua senha"
        testID="folha-senha"
      >
        <Text style={estilos.legenda}>
          A senha prova que é você quem está pedindo — e não alguém com o aparelho desbloqueado na
          mão.
        </Text>
        <CampoTexto
          rotulo="Senha atual"
          secureTextEntry
          value={senha}
          onChangeText={setSenha}
          testID="campo-senha-exclusao"
        />
        {excluir.error ? (
          <Aviso tom="erro" mensagem={mensagemDe(excluir.error)} testID="erro-senha-exclusao" />
        ) : null}
        <Botao
          titulo="Excluir definitivamente"
          carregando={excluir.isPending}
          disabled={senha.length === 0}
          onPress={confirmarExclusao}
          testID="botao-confirmar-exclusao"
        />
      </FolhaInferior>
    </SafeAreaView>
  );
}

function Conquista({ conquista }: { conquista: ConquistaResponse }) {
  return (
    <Card estilo={conquista.conquistada ? estilos.conquistada : undefined}>
      <Text style={estilos.valor}>
        {conquista.conquistada ? '★ ' : '☆ '}
        {conquista.titulo}
      </Text>
      <Text style={estilos.legenda}>{conquista.descricao}</Text>
      {/* O catálogo vem INTEIRO do servidor, inclusive o que falta — é o que diz ao usuário qual é
          o próximo objetivo. Uma lista só de medalhas ganhas não orienta ninguém. */}
      {!conquista.conquistada ? (
        <>
          <BarraProgresso
            valor={conquista.progresso}
            meta={conquista.meta}
            cor={cores.ambar}
            rotuloAcessivel={`${conquista.progresso} de ${conquista.meta}`}
            testID={`progresso-${conquista.codigo}`}
          />
          <Text style={estilos.legenda}>
            {conquista.progresso} de {conquista.meta}
          </Text>
        </>
      ) : null}
    </Card>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  conteudo: { padding: espaco.lg, gap: espaco.md },
  folha: { gap: espaco.md, paddingBottom: espaco.lg },
  titulo: { ...tipografia.titulo, color: cores.tinta },
  handle: { ...tipografia.corpo, color: textoAcessivel.suave },
  subtitulo: { ...tipografia.subtitulo, color: cores.tinta, marginTop: espaco.sm },
  rotulo: { ...tipografia.rotulo, color: textoAcessivel.suave },
  valor: { ...tipografia.corpo, color: cores.tinta },
  legenda: { ...tipografia.legenda, color: textoAcessivel.suave },
  linhaNivel: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  nivel: { ...tipografia.subtitulo, color: cores.verdeEscuro },
  xp: { ...tipografia.rotulo, color: textoAcessivel.ambar },
  conquistada: { borderWidth: 1, borderColor: cores.verdePrimario },
  consentimento: { flexDirection: 'row', alignItems: 'center', gap: espaco.md },
  consentimentoTexto: { flex: 1, gap: 2 },
});
