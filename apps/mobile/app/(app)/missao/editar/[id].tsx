import { useLocalSearchParams, useRouter } from 'expo-router';
import { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Aviso } from '@/components/Aviso';
import { EsqueletoMissaoCard } from '@/components/Esqueleto';
import { TituloTela } from '@/components/TituloTela';
import { FormularioMissao } from '@/features/missoes/FormularioMissao';
import { useAtualizarMissao, useMissao } from '@/features/missoes/hooks';
import type { AtualizarMissaoRequest, MissaoResponse } from '@/api/tipos';
import type { CriarMissaoForm } from '@/schemas';
import { cores, espaco, textoAcessivel, tipografia } from '@/theme';

/**
 * Edição de missão em RASCUNHO ou ABERTA.
 *
 * Rota `/missao/editar/<id>`, e não `/missao/<id>/editar`: a segunda exigiria transformar
 * `missao/[id].tsx` num diretório, e o ganho seria estético. **Fora da allowlist de deep link de
 * propósito** — `src/lib/__tests__/deepLink.test.ts` rejeita `missao/<id>/editar` vindo de fora, e
 * essa recusa continua certa: só se chega aqui de dentro do app, já autenticado.
 *
 * O servidor RECALCULA a recompensa a cada edição de rascunho (ADR 0036), então a tela não promete
 * que o valor fica igual — a prévia dentro do formulário mostra o valor novo enquanto se digita.
 */
export default function EditarMissao() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const missao = useMissao(id);
  const atualizar = useAtualizarMissao(id);

  const valoresIniciais = useMemo(
    () => (missao.data ? paraFormulario(missao.data) : undefined),
    [missao.data],
  );

  function enviar(dados: CriarMissaoForm) {
    atualizar.mutate(paraPatch(dados, missao.data?.status === 'RASCUNHO'), {
      // `back()`, não `replace()`: quem edita veio de algum lugar — do detalhe ou da lista de
      // rascunhos — e deve voltar para lá. `replace` para o detalhe deixaria quem veio dos
      // rascunhos sem caminho de volta para a lista que estava percorrendo.
      onSuccess: () => router.back(),
    });
  }

  if (missao.isLoading) {
    return (
      <SafeAreaView style={estilos.raiz} testID="tela-editar-missao">
        <View style={estilos.conteudo}>
          <EsqueletoMissaoCard />
        </View>
      </SafeAreaView>
    );
  }

  if (missao.isError || !missao.data || !valoresIniciais) {
    return (
      <SafeAreaView style={estilos.raiz} testID="tela-editar-missao">
        <View style={estilos.conteudo}>
          <TituloTela>Editar missão</TituloTela>
          <Aviso
            tom="erro"
            titulo="Não foi possível abrir esta missão"
            mensagem="Ela pode ter sido cancelada, ou não ser sua."
            testID="erro-carregar-edicao"
          />
        </View>
      </SafeAreaView>
    );
  }

  const editavel = missao.data.status === 'RASCUNHO' || missao.data.status === 'ABERTA';

  if (!editavel) {
    return (
      <SafeAreaView style={estilos.raiz} testID="tela-editar-missao">
        <View style={estilos.conteudo}>
          <TituloTela>Editar missão</TituloTela>
          <Aviso
            tom="atencao"
            titulo="Esta missão não pode mais ser editada"
            mensagem="Depois de aceita, o que está combinado vale para quem aceitou."
            testID="erro-carregar-edicao"
          />
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={estilos.raiz} testID="tela-editar-missao">
      <TituloTela>Editar missão</TituloTela>
      {missao.data.status === 'ABERTA' ? (
        <Text style={estilos.ajuda}>
          Esta missão já está publicada: a recompensa está congelada e não muda mais.
        </Text>
      ) : null}
      <FormularioMissao
        modo="editar"
        valoresIniciais={valoresIniciais}
        rotuloEnvio="Salvar alterações"
        enviando={atualizar.isPending}
        erro={atualizar.error}
        aoEnviar={enviar}
        nota={
          missao.data.status === 'RASCUNHO'
            ? 'Enquanto é rascunho, a recompensa acompanha o que você mudar aqui.'
            : 'A recompensa já foi congelada na publicação e não muda com esta edição.'
        }
        testID="botao-salvar"
      />
    </SafeAreaView>
  );
}

/**
 * Missão do servidor → valores do formulário.
 *
 * `cep` e `logradouro` podem vir NULOS — o servidor recorta endereço por participação. Aqui nunca
 * vêm, porque só o criador edita e criador sempre participa; o `?? ''` existe para que uma mudança
 * naquele recorte apareça como campo vazio em vez de crash.
 */
function paraFormulario(m: MissaoResponse): Partial<CriarMissaoForm> {
  return {
    categoria: m.categoria,
    titulo: m.titulo,
    descricao: m.descricao,
    complexidade: m.pesoKg !== null && m.volumeL !== null ? undefined : m.complexidade,
    pesoKg: m.pesoKg ?? undefined,
    volumeL: m.volumeL ?? undefined,
    origemLat: m.origemLat ?? 0,
    origemLon: m.origemLon ?? 0,
    cep: m.cep ?? '',
    logradouro: m.logradouro ?? '',
    bairro: m.bairro,
    cidade: m.cidade,
    uf: m.uf,
    raioCheckinM: m.raioCheckinM,
    janelaInicio: new Date(m.janelaInicio),
    janelaFim: new Date(m.janelaFim),
    pontoCustodiaId: m.pontoCustodiaId ?? undefined,
    // A fonte é o que diz se a missão recompensa em token. `tokensRecompensa === 0` diria o mesmo
    // hoje, mas pela coincidência de a calculadora ter piso de 1 — e não por contrato.
    recompensaEmToken: m.fontePote !== 'SEM_TOKEN',
  };
}

/**
 * Formulário → corpo do PATCH.
 *
 * Manda tudo, inclusive o que não mudou: o servidor trata ausente como "não alterar", então enviar
 * o valor atual é idempotente. Um diff aqui economizaria bytes e criaria uma classe de bug —
 * campo que o diff julgou igual por comparação frouxa não seria enviado, e a edição sumiria sem
 * erro.
 *
 * `complexidade` só vai quando não há peso e volume: com os dois, o servidor deriva e recusa o
 * valor declarado com 422, exatamente como a criação recusa com 400.
 *
 * **`complexidade` e `recompensaEmToken` só vão em RASCUNHO.** Fora dele os dois são 409: a partir
 * de ABERTA a recompensa é promessa feita a quem está prestes a aceitar, e o servidor não deixa
 * mudá-la. Mandá-los mesmo assim faria toda edição de missão publicada falhar — inclusive as que
 * só corrigem uma vírgula do título.
 */
function paraPatch(dados: CriarMissaoForm, rascunho: boolean): AtualizarMissaoRequest {
  const derivaDoObjeto = dados.pesoKg !== undefined && dados.volumeL !== undefined;
  return {
    titulo: dados.titulo,
    descricao: dados.descricao,
    cep: dados.cep,
    logradouro: dados.logradouro,
    bairro: dados.bairro,
    cidade: dados.cidade,
    uf: dados.uf,
    origemLat: dados.origemLat,
    origemLon: dados.origemLon,
    janelaInicio: dados.janelaInicio.toISOString(),
    janelaFim: dados.janelaFim.toISOString(),
    raioCheckinM: dados.raioCheckinM,
    pesoKg: dados.pesoKg,
    volumeL: dados.volumeL,
    complexidade: rascunho && !derivaDoObjeto ? dados.complexidade : undefined,
    recompensaEmToken: rascunho ? dados.recompensaEmToken : undefined,
  };
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: cores.papel },
  conteudo: { padding: espaco.lg, gap: espaco.md },
  ajuda: {
    ...tipografia.legenda,
    color: textoAcessivel.suave,
    paddingHorizontal: espaco.lg,
  },
});
