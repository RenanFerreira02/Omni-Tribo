import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, ScrollView, Share, StyleSheet, Switch, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { logout } from '@/api/auth';
import { mensagemDe } from '@/api/erros';
import type { ConquistaResponse, ConsentimentoResponse, TipoConsentimento } from '@/api/tipos';
import { Aviso } from '@/components/Aviso';
import { BarraProgresso } from '@/components/BarraProgresso';
import { Botao } from '@/components/Botao';
import { CampoTexto } from '@/components/CampoTexto';
import { Card } from '@/components/Card';
import { DialogoConfirmacao } from '@/components/DialogoConfirmacao';
import { Esqueleto } from '@/components/Esqueleto';
import { EstadoVazio } from '@/components/EstadoVazio';
import { FolhaInferior } from '@/components/FolhaInferior';
import { TituloTela } from '@/components/TituloTela';
import {
  useConsentimentos,
  useDefinirConsentimento,
  useExcluirConta,
  useExportarDados,
  usePerfil,
} from '@/features/perfil/hooks';
import { useAnuncio } from '@/lib/anunciar';
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

/**
 * Espaço do indicador de "salvando", SEMPRE reservado ao lado do interruptor.
 *
 * Reservado, e não criado quando aparece: um indicador que nasce empurrando o `Switch` para o lado
 * faz a linha inteira saltar no exato momento em que a pessoa está olhando para ela. O custo é uma
 * faixa vazia de 20 dp em cada linha; a alternativa é um salto por toque.
 *
 * Dimensão de elemento não pertence à escala de `espaco` — e o lint que cobra as duas escalas deixa
 * `width` de fora justamente por isso, pedindo em troca que o número seja NOMEADO.
 */
const LARGURA_INDICADOR_SALVANDO = 20;

export default function TelaPerfil() {
  const router = useRouter();
  /**
   * O desfecho falado da última operação de privacidade.
   *
   * Consentimento e exportação eram as duas operações do app cuja FALHA não aparecia em lugar
   * nenhum — nem para leitor de tela, nem para quem enxerga. O switch voltava ao estado anterior
   * sozinho e a interface seguia como se nada tivesse acontecido.
   */
  const [anuncio, setAnuncio] = useState<string | null>(null);
  const queryClient = useQueryClient();
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

  useAnuncio(anuncio);

  /**
   * Qual consentimento está NO AR agora — `undefined` quando nenhum está.
   *
   * `variables` sobrevive ao fim da mutation (é o que permite ao erro abaixo saber de quem era a
   * tentativa), então o `isPending` na frente é o que a torna uma resposta sobre o presente.
   */
  const emVoo = definirConsentimento.isPending ? definirConsentimento.variables?.tipo : undefined;

  async function sair() {
    if (refreshToken) await logout(refreshToken).catch(() => undefined);
    await encerrar();
    // Sair precisa ESQUECER. O `QueryClient` vive pelo processo inteiro e as chaves são globais
    // (`['perfil']`, `['carteira','saldo']`), então sem isto o perfil e o saldo desta conta ficavam
    // em memória — visíveis para a próxima pessoa a entrar no mesmo aparelho, até o primeiro
    // refetch. O login já limpava; a saída, que é onde a expectativa de esquecimento é explícita,
    // não limpava.
    queryClient.clear();
    router.replace('/(auth)/login');
  }

  async function compartilharDados() {
    // A falha da exportação também não era renderizada em lugar nenhum: o botão parava de girar e
    // nada acontecia. Num direito de LGPD, "nada acontecer" é indistinguível de "o app ignorou".
    let dados;
    try {
      dados = await exportar.mutateAsync();
    } catch {
      setAnuncio('Não foi possível exportar seus dados. Tente de novo.');
      return;
    }
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
          // Mesma razão do `sair()`, e aqui é ainda mais forte: a pessoa acabou de exercer o
          // direito ao esquecimento. Deixar perfil e saldo dela no cache do processo seria a
          // contradição mais direta possível do que o botão promete.
          queryClient.clear();
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
        <TituloTela>{p.nome}</TituloTela>
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
        <TituloTela nivel="secao">Conquistas</TituloTela>
        {p.conquistas.map((conquista) => (
          <Conquista key={conquista.codigo} conquista={conquista} />
        ))}

        {/* ─── Administração ─────────────────────────────────────────────────────────────── */}
        {/*
          Só aparece para ADMIN, mas ESCONDER NÃO É PROTEGER: a rota é comum e quem protege é o 403
          de `GET /api/v1/admin/impacto`. O `papel` daqui vem do perfil, que vem do servidor — não é
          decisão do cliente, é reflexo dela. A tela existe fora das abas justamente para não ocupar
          espaço permanente numa navegação que todo usuário vê.
        */}
        {p.papel === 'ADMIN' ? (
          <>
            <TituloTela nivel="secao">Administração</TituloTela>
            <Botao
              titulo="Painel de impacto"
              variante="secundario"
              onPress={() => router.push('/impacto')}
              testID="botao-impacto"
            />
          </>
        ) : null}

        {/* ─── Privacidade ───────────────────────────────────────────────────────────────── */}
        <TituloTela nivel="secao">Privacidade e dados</TituloTela>
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
            <LinhaConsentimento
              key={item.tipo}
              item={item}
              // Só a linha TOCADA trava. Antes era `definirConsentimento.isPending` cru nos três, e
              // como o `Switch` nativo esmaece quando desabilitado, mexer num apagava e acendia
              // todos — o "piscar" que originou esta correção. `variables` é o que a mutation em
              // voo carrega, então não é preciso estado novo para saber qual delas é.
              salvando={emVoo === item.tipo}
              // O erro pertence à linha que falhou, não à folha. Antes era um `Aviso` no topo, que
              // dizia que ALGO falhou sem dizer o quê — num controle de LGPD, saber qual não é
              // detalhe. Aqui `variables` é lido sem checar `isPending`: no erro a mutation já
              // parou, e é justamente aí que se quer saber de quem era a tentativa.
              erro={
                definirConsentimento.error && definirConsentimento.variables?.tipo === item.tipo
                  ? mensagemDe(definirConsentimento.error)
                  : null
              }
              aoAlternar={(concedido) =>
                definirConsentimento.mutate(
                  { tipo: item.tipo, concedido },
                  {
                    onSuccess: () =>
                      setAnuncio(
                        `${ROTULO_CONSENTIMENTO[item.tipo].titulo}: ${concedido ? 'autorizado' : 'revogado'}.`,
                      ),
                    onError: () =>
                      setAnuncio(
                        `Não foi possível alterar ${ROTULO_CONSENTIMENTO[item.tipo].titulo}. O ajuste não foi salvo.`,
                      ),
                  },
                )
              }
            />
          ))}

          <Text style={estilos.rotulo}>Seus dados</Text>
          <Botao
            titulo="Exportar meus dados"
            variante="secundario"
            carregando={exportar.isPending}
            onPress={() => void compartilharDados()}
            testID="botao-exportar"
          />
          {exportar.error ? (
            <Aviso tom="erro" mensagem={mensagemDe(exportar.error)} testID="erro-exportacao" />
          ) : null}
          <Text style={estilos.legenda}>
            Um arquivo com tudo que a plataforma guarda sobre você: cadastro, consentimentos,
            missões, lançamentos e check-ins. Sem senha nem chaves de acesso.
          </Text>

          <Text style={estilos.rotulo}>Excluir conta</Text>
          <Botao
            titulo="Excluir minha conta"
            variante="texto"
            hint="Apaga seu nome, e-mail e arroba, e encerra a conta. Não tem volta."
            onPress={() => setConfirmandoExclusao(true)}
            testID="botao-excluir-conta"
          />
          <Text style={estilos.legenda}>
            Seu nome, e-mail e @ são apagados e a conta é encerrada. O histórico contábil das
            missões permanece sem qualquer ligação com você — é o que a lei exige guardar.
          </Text>
          {/* O erro da exclusão NÃO é renderizado aqui, e havia um `Aviso` duplicado neste ponto.
              Quem dispara a ação é a folha da senha, e é lá que a falha aparece — com as duas
              montadas, a mesma mensagem era lida duas vezes pelo leitor de tela, de duas regiões
              vivas distintas. */}
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
          // FECHA a folha de privacidade antes de abrir a da senha. Sem isto as duas ficavam
          // montadas: os dois véus de 35% se somavam para ~58% de escurecimento, e fechar a folha
          // da senha devolvia a pessoa à de privacidade em vez de à tela de perfil.
          setPrivacidadeAberta(false);
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

/**
 * Uma linha de consentimento: rótulo, descrição, interruptor — e o estado DAQUELA linha.
 *
 * Componente de módulo, e não função declarada dentro de `TelaPerfil`: ali ele seria um tipo novo a
 * cada render do perfil, e o React remontaria os três `Switch` sempre que qualquer estado da tela
 * mudasse. Seria reintroduzir por outro caminho exatamente o defeito que esta mudança fecha.
 */
function LinhaConsentimento({
  item,
  salvando,
  erro,
  aoAlternar,
}: {
  item: ConsentimentoResponse;
  salvando: boolean;
  erro: string | null;
  aoAlternar: (concedido: boolean) => void;
}) {
  const rotulo = ROTULO_CONSENTIMENTO[item.tipo];

  return (
    <View style={estilos.consentimentoBloco}>
      <View style={estilos.consentimento}>
        <View style={estilos.consentimentoTexto}>
          <Text style={estilos.valor}>{rotulo.titulo}</Text>
          <Text style={estilos.legenda}>{rotulo.descricao}</Text>
        </View>
        {/* Faixa SEMPRE presente: ver `LARGURA_INDICADOR_SALVANDO`. O indicador entra e sai dela
            sem mover o interruptor de lugar. */}
        <View style={estilos.indicadorSalvando}>
          {salvando ? (
            <ActivityIndicator
              size="small"
              color={cores.verdePrimario}
              testID={`salvando-${item.tipo}`}
            />
          ) : null}
        </View>
        <Switch
          value={item.concedido}
          onValueChange={aoAlternar}
          // O Switch nativo tem ~31 pt de altura no iOS, abaixo dos 44 da WCAG 2.5.5, e não
          // aceita padding. `hitSlop` amplia só a área sensível, sem mexer no layout.
          hitSlop={8}
          disabled={salvando}
          accessibilityLabel={rotulo.titulo}
          // `busy` é o que diz ao leitor de tela que o desabilitado é TEMPORÁRIO. Sem ele, o
          // TalkBack anuncia só "desativado", indistinguível de um controle que a pessoa não pode
          // usar — e o indicador visual ao lado não existe para quem não enxerga.
          accessibilityState={{ disabled: salvando, busy: salvando }}
          trackColor={{ true: cores.verdePrimario, false: cores.linha }}
          testID={`consentimento-${item.tipo}`}
        />
      </View>
      {/*
        O erro do consentimento NÃO ERA RENDERIZADO EM LUGAR NENHUM. Revogar podia falhar, o switch
        voltava sozinho e a pessoa ficava acreditando que havia revogado — num controle de LGPD, que
        é onde a consequência de acreditar errado é maior. Hoje ele é renderizado, e DENTRO da linha
        a que pertence.
      */}
      {erro ? <Aviso tom="erro" mensagem={erro} testID="erro-consentimento" /> : null}
    </View>
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
  consentimentoBloco: { gap: espaco.sm },
  consentimento: { flexDirection: 'row', alignItems: 'center', gap: espaco.md },
  consentimentoTexto: { flex: 1, gap: espaco.xxs },
  indicadorSalvando: { width: LARGURA_INDICADOR_SALVANDO, alignItems: 'center' },
});
